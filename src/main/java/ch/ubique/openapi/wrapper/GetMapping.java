package ch.ubique.openapi.wrapper;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;

public final class GetMapping implements org.springframework.web.bind.annotation.GetMapping {
    Object obj;

    public GetMapping(Object obj) {
        this.obj = obj;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return (Class<? extends Annotation>) obj.getClass();
    }

    @Override
    public String name() {
        return invokeMethod("name");
    }

    @Override
    public String[] value() {
        return invokeMethod("value");
    }

    @Override
    public String[] path() {
        return invokeMethod("path");
    }

    @Override
    public String[] params() {
        return invokeMethod("params");
    }

    @Override
    public String[] headers() {
        return invokeMethod("headers");
    }

    @Override
    public String[] consumes() {
        return invokeMethod("consumes");
    }

    @Override
    public String[] produces() {
        return invokeMethod("produces");
    }

    private <T> T invokeMethod(String function) {
        try {
            return (T) obj.getClass().getMethod(function, null).invoke(this.obj, null);
        } catch (IllegalAccessException
                | SecurityException
                | NoSuchMethodException
                | InvocationTargetException
                | IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }
}
