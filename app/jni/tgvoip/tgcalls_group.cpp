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
 *
 * TGX Mod: JNI bindings for tgcalls group calls (video chats / voice chats).
 *
 * Mirrors the group part of Telegram-Android's org.telegram.messenger.voip.NativeInstance
 * (makeGroupNativeInstance, setJoinResponsePayload, setIsMuted, onEmitJoinPayload,
 * onAudioLevelsUpdated, onNetworkStateUpdated), adapted to the tgcalls revision that
 * Telegram X pins (GroupInstanceCustomImpl, no broadcast/stream mode, no platformContext).
 *
 * Java side: org.thunderdog.challegram.voip.GroupCallInstance
 */

#include <jni_utils.h>
#include "bridge.h"

#ifndef DISABLE_TGCALLS

#include <tgcalls/StaticThreads.h>
#include <tgcalls/group/GroupInstanceCustomImpl.h>

#include <pthread.h>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>
#include <jni.h>

// Defined in tgvoip.cpp
void DoWithJNI(std::function<void(JNIEnv*)> f);
namespace tgcalls {
  bool initialize(JNIEnv *env);
}

namespace {

// Keeps a global reference to the Java GroupCallInstance object.
// Callbacks from tgcalls threads may arrive after the Java side asked to destroy the instance,
// so every callback checks the reference under the mutex.
class GroupJavaRef {
public:
  GroupJavaRef (JNIEnv *env, jobject thiz) {
    object = env->NewGlobalRef(thiz);
    clazz = (jclass) env->NewGlobalRef(env->GetObjectClass(thiz));
    pthread_mutex_init(&mutex, nullptr);
  }

  ~GroupJavaRef () {
    pthread_mutex_destroy(&mutex);
  }

  void runSafely (const std::function<void(JNIEnv *, jobject, jclass)> &act) {
    pthread_mutex_lock(&mutex);
    if (object != nullptr) {
      jobject obj = object;
      jclass cls = clazz;
      DoWithJNI([&act, obj, cls](JNIEnv *env) {
        act(env, obj, cls);
        if (env->ExceptionCheck()) {
          env->ExceptionDescribe();
          env->ExceptionClear();
        }
      });
    }
    pthread_mutex_unlock(&mutex);
  }

  void release (JNIEnv *env) {
    pthread_mutex_lock(&mutex);
    if (object != nullptr) {
      env->DeleteGlobalRef(object);
      object = nullptr;
    }
    if (clazz != nullptr) {
      env->DeleteGlobalRef(clazz);
      clazz = nullptr;
    }
    pthread_mutex_unlock(&mutex);
  }

private:
  jobject object = nullptr;
  jclass clazz = nullptr;
  pthread_mutex_t mutex;
};

struct GroupCallContext {
  std::unique_ptr<tgcalls::GroupInstanceCustomImpl> instance;
  std::shared_ptr<GroupJavaRef> javaRef;
};

GroupCallContext *toContext (jlong ptr) {
  return jni::jlong_to_ptr<GroupCallContext *>(ptr);
}

}

// static boolean probeGroupCalls(): lets Java check that these bindings are linked in.
JNI_OBJECT_FUNC(jboolean, voip_GroupCallInstance, probeGroupCalls) {
  return JNI_TRUE;
}

JNI_OBJECT_FUNC(jlong, voip_GroupCallInstance, newGroupInstance, jstring jLogPath, jboolean jNoiseSuppression) {
  if (!tgcalls::initialize(env)) {
    return 0;
  }

  std::shared_ptr<GroupJavaRef> javaRef = std::make_shared<GroupJavaRef>(env, thiz);

  std::string logPath = jLogPath != nullptr ? jni::from_jstring(env, jLogPath) : std::string();

  tgcalls::GroupInstanceDescriptor descriptor;
  descriptor.threads = tgcalls::StaticThreads::getThreads();
  descriptor.config.need_log = !logPath.empty();
  descriptor.config.logPath.data = logPath;
  descriptor.networkStateUpdated = [javaRef](tgcalls::GroupNetworkState state) {
    bool isConnected = state.isConnected;
    javaRef->runSafely([isConnected](JNIEnv *env, jobject obj, jclass cls) {
      jmethodID methodId = env->GetMethodID(cls, "onNetworkStateUpdated", "(Z)V");
      if (methodId != nullptr) {
        env->CallVoidMethod(obj, methodId, (jboolean) (isConnected ? JNI_TRUE : JNI_FALSE));
      }
    });
  };
  descriptor.audioLevelsUpdated = [javaRef](tgcalls::GroupLevelsUpdate const &update) {
    std::vector<jint> ssrcs;
    std::vector<jfloat> levels;
    std::vector<jboolean> voice;
    ssrcs.reserve(update.updates.size());
    levels.reserve(update.updates.size());
    voice.reserve(update.updates.size());
    for (const auto &item : update.updates) {
      ssrcs.push_back((jint) item.ssrc);
      levels.push_back((jfloat) (item.value.isMuted ? 0.0f : item.value.level));
      voice.push_back((jboolean) (!item.value.isMuted && item.value.voice ? JNI_TRUE : JNI_FALSE));
    }
    javaRef->runSafely([&ssrcs, &levels, &voice](JNIEnv *env, jobject obj, jclass cls) {
      auto size = (jsize) ssrcs.size();
      jintArray jSsrcs = env->NewIntArray(size);
      jfloatArray jLevels = env->NewFloatArray(size);
      jbooleanArray jVoice = env->NewBooleanArray(size);
      if (jSsrcs == nullptr || jLevels == nullptr || jVoice == nullptr) {
        return;
      }
      if (size > 0) {
        env->SetIntArrayRegion(jSsrcs, 0, size, ssrcs.data());
        env->SetFloatArrayRegion(jLevels, 0, size, levels.data());
        env->SetBooleanArrayRegion(jVoice, 0, size, voice.data());
      }
      jmethodID methodId = env->GetMethodID(cls, "onAudioLevelsUpdated", "([I[F[Z)V");
      if (methodId != nullptr) {
        env->CallVoidMethod(obj, methodId, jSsrcs, jLevels, jVoice);
      }
      env->DeleteLocalRef(jSsrcs);
      env->DeleteLocalRef(jLevels);
      env->DeleteLocalRef(jVoice);
    });
  };
  // No requestMediaChannelDescriptions: tgcalls then treats every unknown incoming SSRC
  // as an audio channel (GroupInstanceCustomInternal::maybeRequestUnknownSsrc), which is
  // enough for audio-only voice chats.
  descriptor.videoContentType = tgcalls::VideoContentType::None;
  descriptor.initialEnableNoiseSuppression = jNoiseSuppression == JNI_TRUE;

  auto *context = new GroupCallContext;
  context->javaRef = javaRef;
  context->instance = std::make_unique<tgcalls::GroupInstanceCustomImpl>(std::move(descriptor));
  context->instance->setIsMuted(true);

  return jni::ptr_to_jlong(context);
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, emitJoinPayload, jlong ptr) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr || context->instance == nullptr) {
    return;
  }
  std::shared_ptr<GroupJavaRef> javaRef = context->javaRef;
  context->instance->emitJoinPayload([javaRef](tgcalls::GroupJoinPayload const &payload) {
    auto ssrc = (jint) payload.audioSsrc;
    std::string json = payload.json;
    javaRef->runSafely([ssrc, &json](JNIEnv *env, jobject obj, jclass cls) {
      jstring jJson = jni::to_jstring(env, json);
      jmethodID methodId = env->GetMethodID(cls, "onEmitJoinPayload", "(ILjava/lang/String;)V");
      if (methodId != nullptr) {
        env->CallVoidMethod(obj, methodId, ssrc, jJson);
      }
      if (jJson != nullptr) {
        env->DeleteLocalRef(jJson);
      }
    });
  });
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, setJoinResponsePayload, jlong ptr, jstring jPayload) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr || context->instance == nullptr || jPayload == nullptr) {
    return;
  }
  std::string payload = jni::from_jstring(env, jPayload);
  context->instance->setJoinResponsePayload(payload);
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, setConnectionMode, jlong ptr, jint jMode) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr || context->instance == nullptr) {
    return;
  }
  // Match with GroupCallInstance.CONNECTION_MODE_*
  context->instance->setConnectionMode(jMode == 1 ?
    tgcalls::GroupConnectionMode::GroupConnectionModeRtc :
    tgcalls::GroupConnectionMode::GroupConnectionModeNone
  );
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, setIsMuted, jlong ptr, jboolean jMuted) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr || context->instance == nullptr) {
    return;
  }
  context->instance->setIsMuted(jMuted == JNI_TRUE);
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, setNoiseSuppressionEnabled, jlong ptr, jboolean jEnabled) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr || context->instance == nullptr) {
    return;
  }
  context->instance->setIsNoiseSuppressionEnabled(jEnabled == JNI_TRUE);
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, setVolume, jlong ptr, jint jSsrc, jdouble jVolume) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr || context->instance == nullptr) {
    return;
  }
  context->instance->setVolume((uint32_t) jSsrc, (double) jVolume);
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, removeSsrcs, jlong ptr, jintArray jSsrcs) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr || context->instance == nullptr || jSsrcs == nullptr) {
    return;
  }
  jsize size = env->GetArrayLength(jSsrcs);
  std::vector<uint32_t> ssrcs;
  ssrcs.reserve((size_t) size);
  jint *elements = env->GetIntArrayElements(jSsrcs, nullptr);
  if (elements == nullptr) {
    return;
  }
  for (jsize i = 0; i < size; i++) {
    ssrcs.push_back((uint32_t) elements[i]);
  }
  env->ReleaseIntArrayElements(jSsrcs, elements, JNI_ABORT);
  context->instance->removeSsrcs(std::move(ssrcs));
}

JNI_OBJECT_FUNC(void, voip_GroupCallInstance, destroyGroupInstance, jlong ptr) {
  GroupCallContext *context = toContext(ptr);
  if (context == nullptr) {
    return;
  }
  // Stop callbacks into Java first.
  if (context->javaRef != nullptr) {
    context->javaRef->release(env);
  }
  if (context->instance != nullptr) {
    // Same as Telegram-Android: stop, then destroy on the calling (non-tgcalls) thread.
    // ~GroupInstanceCustomImpl blocks on the media thread, so it must not run there.
    context->instance->stop([] { });
    context->instance.reset();
  }
  delete context;
}

#endif
