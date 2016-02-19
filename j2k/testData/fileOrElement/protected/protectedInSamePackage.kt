package test

class BaseSamePackage internal constructor() {

    internal fun foo() {

    }

    internal var i = 1
}

internal class DerivedSamePackage {
    fun usage1() {
        val base = BaseSamePackage()
        base.foo()
        val i = base.i
    }
}
