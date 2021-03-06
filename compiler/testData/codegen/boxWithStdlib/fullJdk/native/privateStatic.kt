// FULL_JDK

class C {
    companion object {
        private @JvmStatic external fun foo()
    }

    fun bar() {
        foo()
    }
}

fun box(): String {
    try {
        C().bar()
        return "Link error expected"
    }
    catch (e: java.lang.UnsatisfiedLinkError) {
        if (e.message != "C.foo()V") return "Fail 1: " + e.message
    }

    return "OK"
}
