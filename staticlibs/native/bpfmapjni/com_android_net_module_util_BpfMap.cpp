/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <errno.h>
#include <linux/pfkeyv2.h>
#include <sys/socket.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include "nativehelper/scoped_primitive_array.h"
#include "nativehelper/scoped_utf_chars.h"

#include <android-base/unique_fd.h>
#include "BpfSyscallWrappers.h"
#include "bpf/KernelUtils.h"

namespace android {

using ::android::base::unique_fd;

static jint com_android_net_module_util_BpfMap_nativeBpfFdGet(JNIEnv *env, jclass clazz,
        jstring path, jint mode, jint keySize, jint valueSize) {
    ScopedUtfChars pathname(env, path);

    unique_fd fd;
    switch (mode) {
      case 0:
        fd.reset(bpf::mapRetrieveRW(pathname.c_str()));
        break;
      case BPF_F_RDONLY:
        fd.reset(bpf::mapRetrieveRO(pathname.c_str()));
        break;
      case BPF_F_WRONLY:
        fd.reset(bpf::mapRetrieveWO(pathname.c_str()));
        break;
      case BPF_F_RDONLY|BPF_F_WRONLY:
        fd.reset(bpf::mapRetrieveExclusiveRW(pathname.c_str()));
        break;
      default:
        errno = EINVAL;
        break;
    }

    if (!fd.ok()) {
        jniThrowErrnoException(env, "nativeBpfFdGet", errno);
        return -1;
    }

    if (bpf::isAtLeastKernelVersion(4, 14, 0)) {
        // These likely fail with -1 and set errno to EINVAL on <4.14
        if (bpf::bpfGetFdKeySize(fd) != keySize) {
            jniThrowErrnoException(env, "nativeBpfFdGet KeySize", EBADFD);
            return -1;
        }
        if (bpf::bpfGetFdValueSize(fd) != valueSize) {
            jniThrowErrnoException(env, "nativeBpfFdGet ValueSize", EBADFD);
            return -1;
        }
    }

    return fd.release();
}

static void com_android_net_module_util_BpfMap_nativeWriteToMapEntry(JNIEnv *env, jobject self,
        jint fd, jbyteArray key, jbyteArray value, jint flags) {
    ScopedByteArrayRO keyRO(env, key);
    ScopedByteArrayRO valueRO(env, value);

    int ret = bpf::writeToMapEntry(static_cast<int>(fd), keyRO.get(), valueRO.get(),
            static_cast<int>(flags));

    if (ret) jniThrowErrnoException(env, "nativeWriteToMapEntry", errno);
}

static jboolean throwIfNotEnoent(JNIEnv *env, const char* functionName, int ret, int err) {
    if (ret == 0) return true;

    if (err != ENOENT) jniThrowErrnoException(env, functionName, err);
    return false;
}

static jboolean com_android_net_module_util_BpfMap_nativeDeleteMapEntry(JNIEnv *env, jobject self,
        jint fd, jbyteArray key) {
    ScopedByteArrayRO keyRO(env, key);

    // On success, zero is returned.  If the element is not found, -1 is returned and errno is set
    // to ENOENT.
    int ret = bpf::deleteMapEntry(static_cast<int>(fd), keyRO.get());

    return throwIfNotEnoent(env, "nativeDeleteMapEntry", ret, errno);
}

static jboolean com_android_net_module_util_BpfMap_nativeGetNextMapKey(JNIEnv *env, jobject self,
        jint fd, jbyteArray key, jbyteArray nextKey) {
    // If key is found, the operation returns zero and sets the next key pointer to the key of the
    // next element.  If key is not found, the operation returns zero and sets the next key pointer
    // to the key of the first element.  If key is the last element, -1 is returned and errno is
    // set to ENOENT.  Other possible errno values are ENOMEM, EFAULT, EPERM, and EINVAL.
    ScopedByteArrayRW nextKeyRW(env, nextKey);
    int ret;
    if (key == nullptr) {
        // Called by getFirstKey. Find the first key in the map.
        ret = bpf::getNextMapKey(static_cast<int>(fd), nullptr, nextKeyRW.get());
    } else {
        ScopedByteArrayRO keyRO(env, key);
        ret = bpf::getNextMapKey(static_cast<int>(fd), keyRO.get(), nextKeyRW.get());
    }

    return throwIfNotEnoent(env, "nativeGetNextMapKey", ret, errno);
}

static jboolean com_android_net_module_util_BpfMap_nativeFindMapEntry(JNIEnv *env, jobject self,
        jint fd, jbyteArray key, jbyteArray value) {
    ScopedByteArrayRO keyRO(env, key);
    ScopedByteArrayRW valueRW(env, value);

    // If an element is found, the operation returns zero and stores the element's value into
    // "value".  If no element is found, the operation returns -1 and sets errno to ENOENT.
    int ret = bpf::findMapEntry(static_cast<int>(fd), keyRO.get(), valueRW.get());

    return throwIfNotEnoent(env, "nativeFindMapEntry", ret, errno);
}

static void com_android_net_module_util_BpfMap_nativeSynchronizeKernelRCU(JNIEnv *env,
                                                                          jclass clazz) {
    const int pfSocket = socket(AF_KEY, SOCK_RAW | SOCK_CLOEXEC, PF_KEY_V2);

    if (pfSocket < 0) {
        jniThrowErrnoException(env, "nativeSynchronizeKernelRCU:socket", errno);
        return;
    }

    if (close(pfSocket)) {
        jniThrowErrnoException(env, "nativeSynchronizeKernelRCU:close", errno);
        return;
    }
    return;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeBpfFdGet", "(Ljava/lang/String;III)I",
        (void*) com_android_net_module_util_BpfMap_nativeBpfFdGet },
    { "nativeWriteToMapEntry", "(I[B[BI)V",
        (void*) com_android_net_module_util_BpfMap_nativeWriteToMapEntry },
    { "nativeDeleteMapEntry", "(I[B)Z",
        (void*) com_android_net_module_util_BpfMap_nativeDeleteMapEntry },
    { "nativeGetNextMapKey", "(I[B[B)Z",
        (void*) com_android_net_module_util_BpfMap_nativeGetNextMapKey },
    { "nativeFindMapEntry", "(I[B[B)Z",
        (void*) com_android_net_module_util_BpfMap_nativeFindMapEntry },
    { "nativeSynchronizeKernelRCU", "()V",
        (void*) com_android_net_module_util_BpfMap_nativeSynchronizeKernelRCU },

};

int register_com_android_net_module_util_BpfMap(JNIEnv* env, char const* class_name) {
    return jniRegisterNativeMethods(env,
            class_name,
            gMethods, NELEM(gMethods));
}

}; // namespace android
