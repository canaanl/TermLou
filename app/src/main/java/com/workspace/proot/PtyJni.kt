package com.workspace.proot

/**
 * terminal-view 的 JNI 类是包私有，App 侧经反射调用。
 * 方法签名对齐 termux terminal-view（createSubprocess / setPtyWindowSize）。
 */
object PtyJni {
    private val cls: Class<*> by lazy {
        runCatching { System.loadLibrary("termux") }
        Class.forName("com.termux.terminal.JNI")
    }

    fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processId: IntArray,
        rows: Int,
        columns: Int
    ): Int {
        val m = cls.getMethod(
            "createSubprocess",
            String::class.java, String::class.java,
            Array<String>::class.java, Array<String>::class.java,
            IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        return (m.invoke(null, cmd, cwd, args, envVars, processId, rows, columns) as Number).toInt()
    }

    fun setPtyWindowSize(fd: Int, rows: Int, columns: Int) {
        val m = cls.getMethod(
            "setPtyWindowSize",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        m.invoke(null, fd, rows, columns)
    }
}
