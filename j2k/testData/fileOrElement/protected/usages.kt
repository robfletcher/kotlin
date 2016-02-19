package test

class BaseProtectedConstructor {
    internal fun usageInConstructor() {

    }

    internal fun usageInPropertyInitializer(): Int {
        return 1
    }

    internal fun usageInStaticInit() {

    }

    internal fun usageInMethod() {

    }
}

internal class DerivedSamePackage {
    init {
        BaseProtectedConstructor().usageInConstructor()
    }

    private val i = BaseProtectedConstructor().usageInPropertyInitializer()

    fun usage() {
        BaseProtectedConstructor().usageInMethod()
    }

    companion object {

        init {
            BaseProtectedConstructor().usageInStaticInit()
        }
    }
}
