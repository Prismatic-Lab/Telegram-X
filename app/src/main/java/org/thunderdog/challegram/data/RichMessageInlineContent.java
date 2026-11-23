/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.data;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.util.text.FormattedText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Flattens the blocks of a full rich message (article) into a list of parts that can be drawn
 * inside a message bubble: chunks of formatted text and media previews.
 *
 * Structure is kept with text styles: headings are bold, quotes are marked and italic,
 * code is monospace, list items keep their labels, tables are shown row by row.
 * Safe to call on a background thread.
 */
public final class RichMessageInlineContent {
  /** Text chunks are split at this length, so each one is laid out and drawn on its own. */
  private static final int MAX_CHUNK_LENGTH = 1500;

  public static final class Part {
    @Nullable public final FormattedText text;
    @Nullable public final TdApi.PageBlock media;

    Part (@Nullable FormattedText text, @Nullable TdApi.PageBlock media) {
      this.text = text;
      this.media = media;
    }

    public boolean isMedia () {
      return media != null;
    }

    /** Rough "cost" of the part, used to render long articles in portions. */
    public int getWeight () {
      return text != null ? text.text.length() : 400;
    }
  }

  private final ViewController<?> context;
  private final TdlibUi.UrlOpenParameters openParameters;
  private final ArrayList<Part> parts = new ArrayList<>();
  private final ArrayList<FormattedText> pending = new ArrayList<>();
  private int pendingLength;

  @Nullable private String skipTitle;
  private boolean skipFirstMedia;
  private boolean hadContent;

  private RichMessageInlineContent (ViewController<?> context, TdlibUi.UrlOpenParameters openParameters) {
    this.context = context;
    this.openParameters = openParameters;
  }

  /**
   * @param skipTitle title already shown above the content, its block is skipped
   * @param skipFirstMedia whether the first media before any text is already shown as the cover
   */
  public static List<Part> build (ViewController<?> context, TdApi.RichMessage richMessage, TdlibUi.UrlOpenParameters openParameters, @Nullable String skipTitle, boolean skipFirstMedia) {
    RichMessageInlineContent b = new RichMessageInlineContent(context, openParameters);
    b.skipTitle = skipTitle;
    b.skipFirstMedia = skipFirstMedia;
    for (TdApi.PageBlock block : richMessage.blocks) {
      b.appendBlock(block, "");
    }
    b.flush();
    return b.parts;
  }

  // Text helpers

  private static TdApi.RichText plain (String text) {
    return new TdApi.RichTextPlain(text);
  }

  private static TdApi.RichText concat (TdApi.RichText... texts) {
    return new TdApi.RichTexts(texts);
  }

  private static TdApi.RichText bold (TdApi.RichText text) {
    return new TdApi.RichTextBold(text);
  }

  private static TdApi.RichText italic (TdApi.RichText text) {
    return new TdApi.RichTextItalic(text);
  }

  private static TdApi.RichText fixed (TdApi.RichText text) {
    return new TdApi.RichTextFixed(text);
  }

  private static TdApi.RichText withIndent (String indent, TdApi.RichText text) {
    return StringUtils.isEmpty(indent) ? text : concat(plain(indent), text);
  }

  private void appendText (@Nullable TdApi.RichText richText) {
    if (richText == null || Td.isEmpty(richText)) {
      return;
    }
    FormattedText text = FormattedText.parseRichText(context, richText, openParameters);
    if (StringUtils.isEmpty(text.text.trim())) {
      return;
    }
    hadContent = true;
    if (pendingLength > 0 && pendingLength + text.text.length() > MAX_CHUNK_LENGTH) {
      flush();
    }
    pending.add(text);
    pendingLength += text.text.length();
  }

  private void appendCaption (@Nullable TdApi.PageBlockCaption caption, String indent) {
    if (caption == null) {
      return;
    }
    if (!Td.isEmpty(caption.text)) {
      appendText(withIndent(indent, italic(caption.text)));
    }
    if (!Td.isEmpty(caption.credit)) {
      appendText(withIndent(indent, italic(concat(plain("— "), caption.credit))));
    }
  }

  private void appendMedia (TdApi.PageBlock block) {
    if (!hadContent && skipFirstMedia) {
      skipFirstMedia = false;
      return;
    }
    skipFirstMedia = false;
    flush();
    parts.add(new Part(null, block));
    hadContent = true;
  }

  private void flush () {
    if (!pending.isEmpty()) {
      parts.add(new Part(FormattedText.concat("\n\n", pending.toArray(new FormattedText[0])), null));
      pending.clear();
      pendingLength = 0;
    }
  }

  private boolean isSkippedTitle (TdApi.RichText text) {
    if (skipTitle != null && !hadContent) {
      String value = TD.getText(text).trim();
      if (skipTitle.equals(value)) {
        skipTitle = null;
        return true;
      }
    }
    return false;
  }

  // Blocks

  private void appendBlocks (TdApi.PageBlock[] blocks, String indent) {
    if (blocks != null) {
      for (TdApi.PageBlock block : blocks) {
        appendBlock(block, indent);
      }
    }
  }

  private void appendBlock (TdApi.PageBlock block, String indent) {
    if (block == null) {
      return;
    }
    switch (block.getConstructor()) {
      case TdApi.PageBlockTitle.CONSTRUCTOR: {
        TdApi.RichText title = ((TdApi.PageBlockTitle) block).title;
        if (!isSkippedTitle(title)) {
          appendText(bold(title));
        }
        break;
      }
      case TdApi.PageBlockHeader.CONSTRUCTOR: {
        TdApi.RichText header = ((TdApi.PageBlockHeader) block).header;
        if (!isSkippedTitle(header)) {
          appendText(withIndent(indent, bold(header)));
        }
        break;
      }
      case TdApi.PageBlockSubheader.CONSTRUCTOR: {
        TdApi.RichText header = ((TdApi.PageBlockSubheader) block).subheader;
        if (!isSkippedTitle(header)) {
          appendText(withIndent(indent, bold(header)));
        }
        break;
      }
      case TdApi.PageBlockSectionHeading.CONSTRUCTOR: {
        TdApi.RichText header = ((TdApi.PageBlockSectionHeading) block).text;
        if (!isSkippedTitle(header)) {
          appendText(withIndent(indent, bold(header)));
        }
        break;
      }
      case TdApi.PageBlockKicker.CONSTRUCTOR:
        appendText(withIndent(indent, bold(((TdApi.PageBlockKicker) block).kicker)));
        break;
      case TdApi.PageBlockSubtitle.CONSTRUCTOR:
        appendText(withIndent(indent, ((TdApi.PageBlockSubtitle) block).subtitle));
        break;
      case TdApi.PageBlockAuthorDate.CONSTRUCTOR: {
        TdApi.PageBlockAuthorDate authorDate = (TdApi.PageBlockAuthorDate) block;
        TdApi.RichText text = authorDate.author;
        if (authorDate.publishDate != 0) {
          String date = Lang.getDate(authorDate.publishDate, TimeUnit.SECONDS);
          text = Td.isEmpty(text) ? plain(date) : concat(text, plain(" · " + date));
        }
        appendText(withIndent(indent, italic(text)));
        break;
      }
      case TdApi.PageBlockParagraph.CONSTRUCTOR:
        appendText(withIndent(indent, ((TdApi.PageBlockParagraph) block).text));
        break;
      case TdApi.PageBlockFooter.CONSTRUCTOR:
        appendText(withIndent(indent, italic(((TdApi.PageBlockFooter) block).footer)));
        break;
      case TdApi.PageBlockPreformatted.CONSTRUCTOR:
        appendText(withIndent(indent, fixed(((TdApi.PageBlockPreformatted) block).text)));
        break;
      case TdApi.PageBlockThinking.CONSTRUCTOR:
        appendText(withIndent(indent, italic(((TdApi.PageBlockThinking) block).text)));
        break;
      case TdApi.PageBlockMathematicalExpression.CONSTRUCTOR:
        appendText(withIndent(indent, fixed(plain(((TdApi.PageBlockMathematicalExpression) block).expression))));
        break;
      case TdApi.PageBlockDivider.CONSTRUCTOR:
        appendText(plain("———"));
        break;
      case TdApi.PageBlockAnchor.CONSTRUCTOR:
      case TdApi.PageBlockButtonRow.CONSTRUCTOR:
      case TdApi.PageBlockUnsupported.CONSTRUCTOR:
        break;
      case TdApi.PageBlockList.CONSTRUCTOR: {
        TdApi.PageBlockListItem[] items = ((TdApi.PageBlockList) block).items;
        for (TdApi.PageBlockListItem item : items) {
          String label;
          if (item.hasCheckbox) {
            label = item.isChecked ? "☑" : "☐";
          } else if (!StringUtils.isEmpty(item.label)) {
            label = item.label;
          } else {
            label = "•";
          }
          String itemIndent = indent + label + " ";
          String nestedIndent = indent + "    ";
          boolean first = true;
          for (TdApi.PageBlock itemBlock : item.blocks) {
            if (first && itemBlock.getConstructor() == TdApi.PageBlockParagraph.CONSTRUCTOR) {
              appendText(withIndent(itemIndent, ((TdApi.PageBlockParagraph) itemBlock).text));
            } else {
              if (first) {
                appendText(plain(itemIndent));
              }
              appendBlock(itemBlock, nestedIndent);
            }
            first = false;
          }
          if (item.blocks.length == 0) {
            appendText(plain(itemIndent));
          }
        }
        break;
      }
      case TdApi.PageBlockBlockQuote.CONSTRUCTOR: {
        TdApi.PageBlockBlockQuote quote = (TdApi.PageBlockBlockQuote) block;
        String quoteIndent = indent + "┃ ";
        for (TdApi.PageBlock quoteBlock : quote.blocks) {
          if (quoteBlock.getConstructor() == TdApi.PageBlockParagraph.CONSTRUCTOR) {
            appendText(withIndent(quoteIndent, italic(((TdApi.PageBlockParagraph) quoteBlock).text)));
          } else {
            appendBlock(quoteBlock, quoteIndent);
          }
        }
        if (!Td.isEmpty(quote.credit)) {
          appendText(withIndent(quoteIndent, concat(plain("— "), quote.credit)));
        }
        break;
      }
      case TdApi.PageBlockPullQuote.CONSTRUCTOR: {
        TdApi.PageBlockPullQuote quote = (TdApi.PageBlockPullQuote) block;
        appendText(withIndent(indent + "┃ ", italic(quote.text)));
        if (!Td.isEmpty(quote.credit)) {
          appendText(withIndent(indent + "┃ ", concat(plain("— "), quote.credit)));
        }
        break;
      }
      case TdApi.PageBlockExpandableBlockQuote.CONSTRUCTOR: {
        TdApi.PageBlockExpandableBlockQuote quote = (TdApi.PageBlockExpandableBlockQuote) block;
        appendText(withIndent(indent + "┃ ", italic(quote.text)));
        if (!Td.isEmpty(quote.credit)) {
          appendText(withIndent(indent + "┃ ", concat(plain("— "), quote.credit)));
        }
        break;
      }
      case TdApi.PageBlockCover.CONSTRUCTOR:
        appendBlock(((TdApi.PageBlockCover) block).cover, indent);
        break;
      case TdApi.PageBlockPhoto.CONSTRUCTOR: {
        TdApi.PageBlockPhoto photo = (TdApi.PageBlockPhoto) block;
        if (photo.photo != null) {
          appendMedia(block);
        }
        appendCaption(photo.caption, indent);
        break;
      }
      case TdApi.PageBlockAnimation.CONSTRUCTOR: {
        TdApi.PageBlockAnimation animation = (TdApi.PageBlockAnimation) block;
        if (animation.animation != null) {
          appendMedia(block);
        }
        appendCaption(animation.caption, indent);
        break;
      }
      case TdApi.PageBlockVideo.CONSTRUCTOR: {
        TdApi.PageBlockVideo video = (TdApi.PageBlockVideo) block;
        if (video.video != null) {
          appendMedia(block);
        }
        appendCaption(video.caption, indent);
        break;
      }
      case TdApi.PageBlockEmbedded.CONSTRUCTOR: {
        TdApi.PageBlockEmbedded embedded = (TdApi.PageBlockEmbedded) block;
        if (embedded.posterPhoto != null) {
          appendMedia(new TdApi.PageBlockPhoto(embedded.posterPhoto, null, embedded.url, false));
        }
        if (!StringUtils.isEmpty(embedded.url)) {
          appendText(withIndent(indent, new TdApi.RichTextUrl(plain(embedded.url), embedded.url, false)));
        }
        appendCaption(embedded.caption, indent);
        break;
      }
      case TdApi.PageBlockEmbeddedPost.CONSTRUCTOR: {
        TdApi.PageBlockEmbeddedPost post = (TdApi.PageBlockEmbeddedPost) block;
        String postIndent = indent + "┃ ";
        if (!StringUtils.isEmpty(post.author)) {
          String author = post.author;
          if (post.date != 0) {
            author += " · " + Lang.getDate(post.date, TimeUnit.SECONDS);
          }
          appendText(withIndent(postIndent, bold(plain(author))));
        }
        appendBlocks(post.blocks, postIndent);
        appendCaption(post.caption, indent);
        break;
      }
      case TdApi.PageBlockCollage.CONSTRUCTOR: {
        TdApi.PageBlockCollage collage = (TdApi.PageBlockCollage) block;
        appendBlocks(collage.blocks, indent);
        appendCaption(collage.caption, indent);
        break;
      }
      case TdApi.PageBlockSlideshow.CONSTRUCTOR: {
        TdApi.PageBlockSlideshow slideshow = (TdApi.PageBlockSlideshow) block;
        appendBlocks(slideshow.blocks, indent);
        appendCaption(slideshow.caption, indent);
        break;
      }
      case TdApi.PageBlockAudio.CONSTRUCTOR: {
        TdApi.PageBlockAudio audio = (TdApi.PageBlockAudio) block;
        if (audio.audio != null) {
          String title = TD.getTitle(audio.audio);
          String subtitle = TD.getSubtitle(audio.audio);
          appendText(withIndent(indent, plain("\uD83C\uDFB5 " + (StringUtils.isEmpty(subtitle) ? title : title + " — " + subtitle))));
        }
        appendCaption(audio.caption, indent);
        break;
      }
      case TdApi.PageBlockVoiceNote.CONSTRUCTOR: {
        appendText(withIndent(indent, plain("\uD83C\uDFA4 " + Lang.getString(R.string.ChatContentVoice))));
        appendCaption(((TdApi.PageBlockVoiceNote) block).caption, indent);
        break;
      }
      case TdApi.PageBlockDocument.CONSTRUCTOR: {
        TdApi.PageBlockDocument document = (TdApi.PageBlockDocument) block;
        if (document.document != null) {
          appendText(withIndent(indent, plain("\uD83D\uDCC4 " + document.document.fileName)));
        }
        appendCaption(document.caption, indent);
        break;
      }
      case TdApi.PageBlockMap.CONSTRUCTOR: {
        TdApi.PageBlockMap map = (TdApi.PageBlockMap) block;
        if (map.location != null) {
          String coordinates = map.location.latitude + ", " + map.location.longitude;
          String url = "https://maps.google.com/maps?q=" + map.location.latitude + "," + map.location.longitude;
          appendText(withIndent(indent, concat(plain("\uD83D\uDCCD "), new TdApi.RichTextUrl(plain(coordinates), url, false))));
        }
        appendCaption(map.caption, indent);
        break;
      }
      case TdApi.PageBlockChatLink.CONSTRUCTOR: {
        TdApi.PageBlockChatLink link = (TdApi.PageBlockChatLink) block;
        if (!StringUtils.isEmpty(link.username)) {
          String url = "https://t.me/" + link.username;
          appendText(withIndent(indent, concat(plain(link.title + " "), new TdApi.RichTextUrl(plain("@" + link.username), url, false))));
        } else {
          appendText(withIndent(indent, plain(link.title)));
        }
        break;
      }
      case TdApi.PageBlockTable.CONSTRUCTOR: {
        TdApi.PageBlockTable table = (TdApi.PageBlockTable) block;
        if (!Td.isEmpty(table.caption)) {
          appendText(withIndent(indent, bold(table.caption)));
        }
        for (TdApi.PageBlockTableCell[] row : table.cells) {
          List<TdApi.RichText> rowTexts = new ArrayList<>();
          for (TdApi.PageBlockTableCell cell : row) {
            if (cell == null) {
              continue;
            }
            if (!rowTexts.isEmpty()) {
              rowTexts.add(plain("  |  "));
            }
            TdApi.RichText cellText = cell.text != null ? cell.text : plain("");
            rowTexts.add(cell.isHeader ? bold(cellText) : cellText);
          }
          if (!rowTexts.isEmpty()) {
            appendText(withIndent(indent, concat(rowTexts.toArray(new TdApi.RichText[0]))));
          }
        }
        break;
      }
      case TdApi.PageBlockDetails.CONSTRUCTOR: {
        TdApi.PageBlockDetails details = (TdApi.PageBlockDetails) block;
        appendText(withIndent(indent, bold(concat(plain("▸ "), details.header))));
        appendBlocks(details.blocks, indent + "    ");
        break;
      }
      case TdApi.PageBlockRelatedArticles.CONSTRUCTOR: {
        TdApi.PageBlockRelatedArticles related = (TdApi.PageBlockRelatedArticles) block;
        if (!Td.isEmpty(related.header)) {
          appendText(withIndent(indent, bold(related.header)));
        }
        for (TdApi.PageBlockRelatedArticle article : related.articles) {
          String title = !StringUtils.isEmpty(article.title) ? article.title : article.url;
          appendText(withIndent(indent + "• ", new TdApi.RichTextUrl(plain(title), article.url, false)));
        }
        break;
      }
      default:
        break;
    }
  }
}
