package test;

public abstract class Base {
    protected Activity mActivity;

    public Base(Activity activity) {
        mActivity = activity;
    }
}

public class Activity {
    public void display() {}
}

public class Derived extends Base {
    public Derived(Activity activity) {
        super(activity);
    }

    private View usage = new View() {
        @Override
        void click() {
            mActivity.display();
        }
    }
}

abstract class View {
    abstract void click();
}
