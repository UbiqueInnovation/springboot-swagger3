package ch.ubique.openapi.wrapper;

import java.lang.annotation.Annotation;

public final class RequestBody extends MethodTranslator
        implements org.springframework.web.bind.annotation.RequestBody {

    public Class<? extends Annotation> annotationType() {
        return (Class<? extends Annotation>) obj.getClass();
    }

    public RequestBody(Object obj) {
        this.obj = obj;
    }

    public boolean required() {
        return invokeMethod("required");
    }
}
