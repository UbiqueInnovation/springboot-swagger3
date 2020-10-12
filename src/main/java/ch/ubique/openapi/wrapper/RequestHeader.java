package ch.ubique.openapi.wrapper;

import java.lang.annotation.Annotation;

public final class RequestHeader extends MethodTranslator
        implements org.springframework.web.bind.annotation.RequestHeader {

    public Class<? extends Annotation> annotationType() {
        return (Class<? extends Annotation>) obj.getClass();
    }

    public RequestHeader(Object obj) {
        this.obj = obj;
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

    public String defaultValue() {
        return invokeMethod("defaultValue");
    }
}
