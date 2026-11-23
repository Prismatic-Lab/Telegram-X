#!/usr/bin/env python3
# TGX Mod: regenerates "Swiss Light.tgx-theme" and "Swiss Dark.tgx-theme" from Blue / Night Black + the Swiss palette below.
# Generates "Swiss Light" / "Swiss Dark" .tgx-theme files for TGX Mod.
import re, collections, sys
import os
T=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'other', 'themes') + os.sep
def parse(fn):
    props, colors = {}, {}
    sec=None
    for line in open(T+fn, encoding='utf-8'):
        line=line.rstrip('\n')
        if line in ('!','@','#'): sec=line; continue
        if sec is None: continue
        i=line.find('//')
        if i!=-1: line=line[:i].strip()
        if ':' not in line: continue
        k,v=line.split(':',1); v=v.strip()
        for n in re.split(r'\s*,\s*', k.strip()):
            if sec=='@': props[n]=v
            elif sec=='#': colors[n]=v
    return props, colors
xml=open(T+'colors-and-properties.xml').read()
colors_sec=re.sub(r'<!--.*?-->', '', xml[xml.index('<colors>'):], flags=re.S)
ALL=list(dict.fromkeys(re.findall(r'<item name="([^"]+)"', colors_sec)))
_, blue = parse('Blue.tgx-theme')
_, nb = parse('Night Black.tgx-theme')
missing=[c for c in ALL if c not in blue]
# fallback colors (declared with fallback=) may be absent in Blue
fallback=dict(re.findall(r'<item name="([^"]+)"[^>]*fallback="([^"]+)"', colors_sec))
light_base=dict(blue)
dark_base=dict(blue); dark_base.update(nb)

def A(a, hexa): # alpha
    return hexa + a

# ---------------- LIGHT -----------------
L = dict(
  ACC='#D0312D',      # signal red
  ACC_SOFT='#EDB3B0',
  INK='#1A1A1A',      # graphite (primary text, primary surfaces)
  INK2='#3A3A3A',     # secondary ink (ticks read, lines)
  GREY='#6E6E6C',     # secondary text (4.9:1 on #FCFCFB)
  GREY2='#8C8C89',    # tertiary (time, muted icons)
  GREY3='#A8A8A5',    # quaternary (placeholders, chat list icons)
  ICON='#555553',
  LINE='#E4E4E0',     # hairline divider
  LINE2='#D6D6D1',
  SURF='#FCFCFB',     # filling / header
  BG='#F1F1EE',       # page background between sections
  CHAT='#F3F3F0',     # plain chat background
  BIN='#FFFFFF',      # incoming bubble
  BOUT='#E7E7E3',     # outgoing bubble (neutral tinted)
  PILL='#E3E3DF',     # date / service pills on plain background
  POS='#3E7F52', NEG='#B3261E',
)
def overrides(P, dark):
    o={}
    def s(names, v):
        for n in names.split():
            o[n]=v
    ACC,INK,INK2,GREY,GREY2,GREY3,ICON,LINE,LINE2,SURF,BG,CHAT,BIN,BOUT,PILL,POS,NEG = [P[k] for k in 'ACC INK INK2 GREY GREY2 GREY3 ICON LINE LINE2 SURF BG CHAT BIN BOUT PILL POS NEG'.split()]
    ONINK = P['ONINK']
    # surfaces
    s('filling headerBackground headerLightBackground overlayFilling headerButton headerPickerBackground', SURF)
    s('background iv_chatLinkBackground iv_textReferenceBackground iv_preBlockBackground', BG)
    s('chatBackground chatKeyboard playerCoverPlaceholder', CHAT)
    s('fillingPressed', P['PRESSED'])
    s('placeholder headerPlaceholder', P['PLACEHOLDER'])
    s('previewBackground', SURF + 'C0')
    s('separator chatSeparator iv_separator bubble_chatSeparator shareSeparator bubbleIn_separator', LINE)
    s('inputInactive fileAttach seekEmpty waveformInactive chatKeyboardButton introSection', LINE2)
    # text
    s('text black bubbleIn_text bubbleOut_text iv_text iv_header iv_pageTitle iv_textMarked iv_textReference ivHeader playerButton headerText headerIcon introSectionActive blockQuoteText bubbleIn_blockQuoteText bubbleOut_blockQuoteText fillingActiveContent bubbleIn_fillingActiveContent bubbleOut_fillingActiveContent drawerText headerPickerText headerTabActiveText profileSectionActiveContent iv_blockQuote', INK)
    s('textLight background_text background_textLight iv_caption iv_pageAuthor iv_pageFooter iv_pageSubtitle headerLightText unreadText nameInactive lineInactive', GREY)
    s('bubbleIn_time bubbleOut_time bubbleOut_progress bubbleOut_separator ticks bubbleOut_ticks controlInactive sliderInactive headerTabInactiveText messageSwipeBackground headerBarCallMuted', GREY2)
    s('textPlaceholder chatListIcon chatListMute iconLight background_icon badgeMuted headerBadgeMuted avatarInactive avatarInactive_big avatarArchivePinned playerCoverIcon togglerInactiveBackground', GREY3)
    s('icon iv_icon ivHeaderIcon headerLightIcon headerButtonIcon circleButtonChatIcon circleButtonOverlayIcon', ICON)
    s('ticksRead bubbleOut_ticksRead messageAuthor bubbleOut_messageAuthor chatListAction chatListVerify inlineText bubbleOut_inlineText inlineIcon bubbleOut_inlineIcon messageVerticalLine bubbleOut_chatVerticalLine blockQuoteLine bubbleIn_blockQuoteLine bubbleOut_blockQuoteLine iv_blockQuoteLine waveformActive bubbleOut_waveformActive seekDone playerButtonActive promo bubbleIn_progress inputActive bubbleOut_codeBlockLine', INK2)
    s('inlineOutline bubbleOut_inlineOutline bubbleIn_outline bubbleOut_outline seekReady', LINE2)
    # accent (sparingly): badges, links, selection, primary filled buttons
    s('badge headerBadge headerTabActive profileSectionActive textLink bubbleIn_textLink bubbleOut_textLink iv_textLink iv_textMarkedLink textNeutral iconActive checkActive controlActive sliderActive togglerActive togglerPositive progress chatSendButton textSearchQueryHighlight fillingPositive notificationLink', ACC)
    s('tooltip_textLink snackbarUpdateAction', P['ACC_ON_DARK'])
    s('textLinkPressHighlight bubbleIn_textLinkPressHighlight bubbleOut_textLinkPressHighlight iv_textLinkPressHighlight iv_textMarkedLinkPressHighlight', ACC + '30')
    s('tooltip_textLinkPressHighlight', P['ACC_ON_DARK'] + '40')
    s('textSelectionHighlight', ACC + '33')
    s('togglerActiveBackground togglerPositiveBackground', P['ACC_SOFT'])
    s('togglerInactive', P['TOGGLER_OFF'])
    s('messageSelection bubble_messageSelection bubble_messageSelectionNoWallpaper', INK + ('1A' if dark else '12'))
    s('fillingActive bubbleIn_fillingActive bubbleOut_fillingActive', INK + '14')
    # graphite primary surfaces (FAB, snackbar, passcode, notification)
    s('circleButtonRegular circleButtonActive circleButtonTheme circleButtonNewChat circleButtonNewGroup circleButtonNewChannel circleButtonNewSecret snackbarUpdate passcode notification notificationPlayer attachPhoto attachFile attachLocation attachContact attachInlineBot avatarSavedMessages avatarSavedMessages_big avatarReplies avatarReplies_big avatarArchive headerRemoveBackground', P['PRIMARY'])
    s('circleButtonRegularIcon circleButtonActiveIcon circleButtonThemeIcon circleButtonNewChatIcon circleButtonNewGroupIcon circleButtonNewChannelIcon circleButtonNewSecretIcon snackbarUpdateText passcodeIcon passcodeText attachText avatar_content', ONINK)
    s('headerRemoveBackgroundHighlight', P['PRIMARY_HL'])
    s('circleButtonChat circleButtonOverlay', BIN)
    s('tooltip', P['TOOLTIP'])
    # semantic
    s('online iconPositive inputPositive textSecure notificationSecure circleButtonPositive headerBarCallIncoming messageCorrectFilling messageCorrectChosenFilling bubbleOut_chatCorrectFilling bubbleOut_chatCorrectChosenFilling', POS)
    s('textNegative fillingNegative iconNegative inputNegative circleButtonNegative togglerNegative badgeFailed headerBadgeFailed messageNegativeLine bubbleOut_chatNegativeFilling fileRed', NEG)
    s('headerBarCallActive', INK2)
    # bubbles
    s('bubbleIn_background', BIN)
    s('bubbleOut_background', BOUT)
    s('bubbleIn_pressed bubbleOut_pressed', INK + '10')
    s('bubbleOut_file file', ICON)
    s('bubbleIn_fillingPositive bubbleIn_fillingPositive_overlay bubbleOut_fillingPositive bubbleOut_fillingPositive_overlay', INK2)
    s('bubbleIn_fillingPositiveContent bubbleIn_fillingPositiveContent_overlay bubbleOut_fillingPositiveContent bubbleOut_fillingPositiveContent_overlay fillingPositiveContent fileContent bubbleOut_fileContent', P['ON_INK2'])
    s('bubbleOut_waveformInactive', P['WAVE_OUT_OFF'])
    s('bubbleOut_codeBlockBackground', INK + '0F')
    s('bubbleOut_codeBlockRipple', INK + '1E')
    # pills on plain (no wallpaper) background
    s('bubble_date_noWallpaper bubble_unread_noWallpaper bubble_mediaReply_noWallpaper bubble_button_noWallpaper', PILL)
    s('bubble_dateText_noWallpaper bubble_unreadText_noWallpaper bubble_mediaReplyText_noWallpaper bubble_buttonText_noWallpaper', P['PILL_TEXT'])
    s('bubble_buttonRipple_noWallpaper', INK + '1A')
    s('bubble_messageCheckOutlineNoWallpaper', GREY2)
    s('unread', P['UNREAD'])
    # member / avatar colours: muted, equal-lightness set
    for name, v in P['NAMES'].items():
        s('avatar%s avatar%s_big name%s line%s' % (name, name, name, name), v)
    s('fileGreen', P['NAMES']['Green']); s('fileYellow', P['NAMES']['Yellow'])
    # radios in theme list
    s('themeSwissLight', '#D0312D'); s('themeSwissDark', '#1A1A1A' if not dark else '#EDEDEB')
    # status bar: flat
    s('statusBar', '#00000000')
    return o

L.update(dict(
  ONINK='#FFFFFF', ON_INK2='#FFFFFF', PRIMARY='#1A1A1A', PRIMARY_HL='#3A3A3A', ACC_ON_DARK='#F0675D',
  PRESSED='#EFEFEC', PLACEHOLDER='#1A1A1A0A', TOGGLER_OFF='#F4F4F2', TOOLTIP='#1A1A1AE6',
  WAVE_OUT_OFF='#CBCBC6', PILL_TEXT='#4A4A48', UNREAD='#ECECE8',
  NAMES=dict(Red='#B9524A', Orange='#BF7440', Yellow='#A98A2E', Green='#5B8A5C', Cyan='#4A8893', Blue='#4F72A3', Violet='#76679F', Pink='#A8627F'),
))
D = dict(
  ACC='#EF5A4F', ACC_SOFT='#6B2A26', INK='#EDEDEB', INK2='#C9C9C6', GREY='#9B9B98', GREY2='#7C7C79', GREY3='#5E5E5B',
  ICON='#A3A3A0', LINE='#232323', LINE2='#333331', SURF='#121212', BG='#0A0A0A', CHAT='#0D0D0D', BIN='#1C1C1C', BOUT='#2A2A28',
  PILL='#222221', POS='#6FB383', NEG='#F2685E',
  ONINK='#121212', ON_INK2='#121212', PRIMARY='#EDEDEB', PRIMARY_HL='#C9C9C6', ACC_ON_DARK='#EF5A4F',
  PRESSED='#1C1C1C', PLACEHOLDER='#FFFFFF0C', TOGGLER_OFF='#8A8A87', TOOLTIP='#2A2A28F2',
  WAVE_OUT_OFF='#4A4A47', PILL_TEXT='#B5B5B2', UNREAD='#1C1C1C',
  NAMES=dict(Red='#D9786F', Orange='#D99560', Yellow='#C9AB55', Green='#7FAE7F', Cyan='#6FAAB4', Blue='#7C9BCB', Violet='#9C8CC6', Pink='#C98AA5'),
)
light=dict(light_base); light.update(overrides(L, False))
dark=dict(dark_base); dark.update(overrides(D, True))
# dark-only fixes
dark.update({'statusBarLegacy':'#00000040', 'bubbleOut_textLink':'#F4766D', 'badge':'#D9443A', 'headerBadge':'#D9443A', 'badgeText':'#FFFFFF', 'badgeMutedText':'#FFFFFF', 'badgeFailedText':'#FFFFFF'})
light.update({'statusBarLegacy':'#00000066', 'bubbleOut_textLink':'#B42A26', 'badgeMuted':'#8C8C89', 'headerBadgeMuted':'#8C8C89'})
for k,v in list(dark.items()):
    pass
unknown=[k for k in list(light)+list(dark) if k not in ALL]
assert not unknown, unknown
def write(fn, tid, name, props, colors):
    out=['!', 'id: %d'%tid, 'name: "%s"'%name, 'time: 1795000000', '@']
    byv=collections.OrderedDict()
    for k,v in props.items(): byv.setdefault(v,[]).append(k)
    for v,ks in byv.items(): out.append('%s: %s'%(', '.join(ks), v))
    out.append('#')
    byc=collections.OrderedDict()
    for k in ALL:
        if k in colors: byc.setdefault(colors[k].upper(),[]).append(k)
    for v,ks in sorted(byc.items(), key=lambda kv: kv[1][0].lower()):
        out.append('%s: %s'%(', '.join(ks), v))
    open(T+fn,'w',encoding='utf-8').write('\n'.join(out)+'\n')
    return len(colors)
common=dict(bubbleCorner='8', bubbleCornerMerged='3', bubbleCornerLegacy='3', bubbleDateCorner='4', dateCorner='4', bubbleOuterMargin='8',
  bubbleOutline='1', bubbleOutlineSize='1', bubbleUnreadShadow='0', replaceShadowsWithSeparators='1', shadowDepth='0',
  imageCorner='2', subtitleAlpha='0.75', avatarRadius='1.0', avatarRadiusForum='0.3', avatarRadiusChatList='-1.0', avatarRadiusChatListForum='-1.0',
  wallpaperOverrideButton='1', wallpaperOverrideDate='1', wallpaperOverrideMediaReply='1', wallpaperOverrideOverlay='1', wallpaperOverrideTime='1', wallpaperOverrideUnread='1')
lp=dict(parentTheme='1', dark='0', lightStatusBar='1', wallpaperUsageId='0'); lp.update(common)
dp=dict(parentTheme='1', dark='1', lightStatusBar='0', wallpaperUsageId='1'); dp.update(common)
print(write('Swiss Light.tgx-theme', 12, 'Swiss Light', lp, light), write('Swiss Dark.tgx-theme', 13, 'Swiss Dark', dp, dark))
print('colors total', len(ALL), 'missing in blue', [c for c in missing if c not in fallback])
