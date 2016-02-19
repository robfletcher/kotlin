package test

abstract class Base(protected var mActivity: Activity)

class Activity {
    fun display() {
    }
}

class Derived(activity: Activity) : Base(activity) {

    private val usage = object : View() {
        override fun click() {
            mActivity.display()
        }
    }
}

internal abstract class View {
    internal abstract fun click()
}
