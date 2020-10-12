package ch.ubique.openapi.wrapper;

import java.lang.annotation.Annotation;

public final class Documentation extends MethodTranslator
        implements ch.ubique.openapi.docannotations.Documentation {

    public Documentation(Object obj) {
        this.obj = obj;
    }

    public Class<? extends Annotation> annotationType() {
        return (Class<? extends Annotation>) obj.getClass();
    }

    public String description() {
        return invokeMethod("description");
    }

    public String example() {
        return invokeMethod("example");
    }

    public String[] responses() {
        return invokeMethod("responses");
    }

    public boolean undocumented() {
        return invokeMethod("undocumented");
    }

    public Class<?> serializedClass() {
        return invokeMethod("serializedClass");
    }
}
