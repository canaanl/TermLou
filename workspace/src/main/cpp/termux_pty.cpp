#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>

#define LOG_TAG "RikkaTermuxJni"

static char *copy_java_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return nullptr;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return nullptr;
    char *copy = strdup(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

static std::vector<char *> copy_java_string_array(JNIEnv *env, jobjectArray values) {
    std::vector<char *> result;
    if (values == nullptr) return result;
    const jsize length = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; i++) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        result.push_back(copy_java_string(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

static void free_string_vector(std::vector<char *> &values) {
    for (char *value: values) {
        free(value);
    }
    values.clear();
}

/** winsize 的 ws_xpixel/ws_ypixel 是 unsigned short，乘积先夹到合法域再存（对齐上游截断语义但不回绕）。 */
static unsigned short clamp_to_ushort(int value) {
    if (value <= 0) return 0;
    if (value >= 0xFFFF) return 0xFFFF;
    return static_cast<unsigned short>(value);
}

static int open_pty_master(char *slave_name, size_t slave_name_size) {
    const int master = posix_openpt(O_RDWR | O_CLOEXEC);
    if (master < 0) return -1;
    if (grantpt(master) != 0 || unlockpt(master) != 0 || ptsname_r(master, slave_name, slave_name_size) != 0) {
        close(master);
        return -1;
    }
    return master;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv *env,
        jclass,
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processId,
        jint rows,
        jint columns,
        jint cellWidth,
        jint cellHeight) {
    char slave_name[128] = {};
    const int master = open_pty_master(slave_name, sizeof(slave_name));
    if (master < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "open pty failed: %s", strerror(errno));
        return -1;
    }

    char *command = copy_java_string(env, cmd);
    char *working_dir = copy_java_string(env, cwd);
    std::vector<char *> java_args = copy_java_string_array(env, args);
    std::vector<char *> java_env = copy_java_string_array(env, envVars);

    std::vector<char *> argv;
    argv.reserve(java_args.size() + 2);
    argv.push_back(command);
    for (char *arg: java_args) {
        argv.push_back(arg);
    }
    argv.push_back(nullptr);

    java_env.push_back(nullptr);

    const pid_t pid = fork();
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "fork failed: %s", strerror(errno));
        close(master);
        free(command);
        free(working_dir);
        free_string_vector(java_args);
        free_string_vector(java_env);
        return -1;
    }

    if (pid == 0) {
        setsid();
        const int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);

        winsize size = {};
        size.ws_row = static_cast<unsigned short>(rows);
        size.ws_col = static_cast<unsigned short>(columns);
        size.ws_xpixel = clamp_to_ushort(static_cast<int>(columns) * cellWidth);
        size.ws_ypixel = clamp_to_ushort(static_cast<int>(rows) * cellHeight);
        ioctl(slave, TIOCSWINSZ, &size);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        if (working_dir != nullptr && working_dir[0] != '\0') {
            chdir(working_dir);
        }

        execve(command, argv.data(), java_env.data());
        _exit(127);
    }

    if (processId != nullptr) {
        jint process_ids[1] = {static_cast<jint>(pid)};
        env->SetIntArrayRegion(processId, 0, 1, process_ids);
    }

    free(command);
    free(working_dir);
    free_string_vector(java_args);
    free_string_vector(java_env);
    return master;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv *, jclass, jint fd, jint rows, jint columns,
                                              jint cellWidth, jint cellHeight) {
    winsize size = {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_xpixel = clamp_to_ushort(static_cast<int>(columns) * cellWidth);
    size.ws_ypixel = clamp_to_ushort(static_cast<int>(rows) * cellHeight);
    ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(JNIEnv *, jclass, jint pid) {
    int status = 0;
    pid_t result;
    // waitpid 可被信号打断（EINTR），不重试会把 -1 当成退出状态误结束会话
    do {
        result = waitpid(pid, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(JNIEnv *, jclass, jint fd) {
    close(fd);
}

// readPty/writePty/read/write 已删除（5.4.1）：上游 JNI 类未声明这四个方法、全仓无任何调用方
// （主终端/LAN 读写均走 Java File 流），无边界校验的死导出只增加维护与攻击面。
