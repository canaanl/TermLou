package com.workspace.proot

/**
 * terminal-view 的 JNI 类是包私有，App 侧经反射调用。
 * 签名对齐 termux terminal-emulator v0.118.3：createSubprocess 9 参 / setPtyWindowSize 5 参，
 * native 侧（自写 libtermux.so）一一对应，Method 只解析一次并缓存。
 * cellWidth/cellHeight 为单格像素（TerminalView 传字体宽/行距），native 乘行列写入
 * ws_xpixel/ws_ypixel；LAN 无字体信息时用 8x16 默认值。
 */
object PtyJni {
    private val cls: Class<*> by lazy {
        runCatching { System.loadLibrary("termux") }
        Class.forName("com.termux.terminal.JNI")
    }

    private val createSubprocessMethod by lazy {
        cls.getDeclaredMethod(
            "createSubprocess",
            String::class.java, String::class.java,
            Array<String>::class.java, Array<String>::class.java,
            IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
    }

    private val setWindowSizeMethod by lazy {
        cls.getDeclaredMethod(
            "setPtyWindowSize",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
    }

    fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processId: IntArray,
        rows: Int,
        columns: Int,
        cellWidth: Int = 8,
        cellHeight: Int = 16
    ): Int {
        return (createSubprocessMethod.invoke(
            null, cmd, cwd, args, envVars, processId, rows, columns, cellWidth, cellHeight
        ) as Number).toInt()
    }

    fun setPtyWindowSize(fd: Int, rows: Int, columns: Int, cellWidth: Int = 8, cellHeight: Int = 16) {
        setWindowSizeMethod.invoke(null, fd, rows, columns, cellWidth, cellHeight)
    }
}
