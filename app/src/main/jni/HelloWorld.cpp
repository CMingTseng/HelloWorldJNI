#include "HelloWorld.h"
#include <stdio.h>

JNIEXPORT void JNICALL
Java_org_kmp_jni_HelloWorld_print(JNIEnv*env,jobject obj) {
     printf("Hello from C++ World!\n");
}