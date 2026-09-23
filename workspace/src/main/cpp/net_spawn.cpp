#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

#define LOG_TAG "TunSpawner"

static char *copy_java_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return nullptr;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return nullptr;
    char *copy = strdup(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

extern char **environ;

/** 只放行子进程可能需要的基础环境项。 */
static bool env_allowed(const char *entry) {
    static const char *const prefixes[] = {
            "PATH=", "HOME=", "TMPDIR=", "LANG=", "LC_",
            "ANDROID_ROOT=", "ANDROID_DATA=", "EXTERNAL_STORAGE="
    };
    for (const char *prefix: prefixes) {
        if (strncmp(entry, prefix, strlen(prefix)) == 0) return true;
    }
    return false;
}

/**
 * execve 用的最小环境：指针直接指向 environ 里的字符串（无拷贝），
 * 必须在 fork 前的父进程上下文构建 —— 继承 ART 全量 environ 属于环境泄露。
 */
static std::vector<char *> build_minimal_env() {
    std::vector<char *> result;
    for (char **entry = environ; entry != nullptr && *entry != nullptr; entry++) {
        if (env_allowed(*entry)) result.push_back(*entry);
    }
    result.push_back(nullptr);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_workspace_proot_TunSpawner_spawnTun2Socks(
        JNIEnv *env,
        jobject,
        jstring executable,
        jobjectArray args,
        jint tunFd,
        jstring logPath) {
    char *exe = copy_java_string(env, executable);
    if (exe == nullptr) return -1;
    char *log = copy_java_string(env, logPath);

    std::vector<char *> argv;
    argv.reserve((args == nullptr ? 0 : env->GetArrayLength(args)) + 2);
    argv.push_back(exe);
    if (args != nullptr) {
        const jsize len = env->GetArrayLength(args);
        for (jsize i = 0; i < len; i++) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(args, i));
            argv.push_back(copy_java_string(env, s));
            env->DeleteLocalRef(s);
        }
    }
    argv.push_back(nullptr);

    // fork 前构建：子进程 execve 只带 allowlist 环境，不再继承 ART 全量 environ
    std::vector<char *> child_env = build_minimal_env();

    const pid_t pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "fork failed: %s", strerror(errno));
        for (char *a : argv) free(a);
        free(log);
        return -1;
    }

    if (pid == 0) {
        if (log != nullptr) {
            const int lfd = open(log, O_WRONLY | O_CREAT | O_TRUNC, 0666);
            if (lfd >= 0) {
                dup2(lfd, STDOUT_FILENO);
                dup2(lfd, STDERR_FILENO);
                if (lfd > STDERR_FILENO) close(lfd);
            }
        }
        if (tunFd != 3) {
            dup2(tunFd, 3);
            if (tunFd > 3) close(tunFd);
        }
        execve(exe, argv.data(), child_env.data());
        _exit(127);
    }

    for (char *a : argv) free(a);
    free(log);
    return static_cast<jint>(pid);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_workspace_proot_TunSpawner_waitPid(JNIEnv *, jobject, jint pid) {
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
Java_com_workspace_proot_TunSpawner_killPid(JNIEnv *, jobject, jint pid) {
    return ::kill(pid, SIGKILL) == 0 ? JNI_TRUE : JNI_FALSE;
}