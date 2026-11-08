#include <jni.h>
#include <errno.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_workspace_proot_ProcessJni_waitPid(JNIEnv *, jobject, jint pid) {
    int status = 0;
    pid_t result;
    do {
        result = waitpid(pid, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_workspace_proot_ProcessJni_killPid(JNIEnv *, jobject, jint pid) {
    return ::kill(pid, SIGKILL) == 0 ? JNI_TRUE : JNI_FALSE;
}
