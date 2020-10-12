package ch.ubique.openapi.wrapper;

import java.lang.annotation.Annotation;

public final class PathVariable extends MethodTranslator
        implements org.springframework.web.bind.annotation.PathVariable {

    public PathVariable(Object obj) {
        this.obj = obj;
    }

    public Class<? extends Annotation> annotationType() {
        return null;
    }

    public String value() {
        return invokeMethod("value");
    }

    public String name() {
        return invokeMethod("name");
    }

    public boolean required() {
        return invokeMethod("required");
    }
}
