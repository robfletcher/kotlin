// FILE: JavaClass.java

public abstract class JavaClass {
    public abstract InnerClass onCreateInner();

    public class InnerClass {

    }
}

// FILE: Kotlin.kt

public class MyWallpaperService : JavaClass() {
    override fun onCreateInner(): JavaClass.InnerClass = MyEngine()

    private inner class MyEngine : JavaClass.InnerClass()
}

fun box(): String {
    return if (MyWallpaperService().onCreateInner() != null) return "OK" else "fail"
}
