package ch.ubique.openapi.wrapper;

import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public final class RequestMapping
        implements org.springframework.web.bind.annotation.RequestMapping {

    Object obj;
    RequestMethod method;

    public RequestMapping(Mapping wrapper) {
        this.obj = wrapper.getObj();
        this.method = wrapper.getMethod();
    }

    public Class<? extends Annotation> annotationType() {
        return (Class<? extends Annotation>) obj.getClass();
    }

    public String name() {
        try {
            return (String) obj.getClass().getMethod("name", null).invoke(obj, null);
        } catch (IllegalAccessException
                | SecurityException
                | NoSuchMethodException
                | InvocationTargetException
                | IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return "";
    }

    public String[] value() {
        return invokeMethod("value");
    }

    public String[] path() {
        return invokeMethod("path");
    }

    public RequestMethod[] method() {

        List<RequestMethod> test = new ArrayList<>();

        // Object[] returnValue =  (Object[])obj.getClass().getMethod("method",
        // null).invoke(this.obj,
        // null);

        // for(Object obj : returnValue) {
        //     test.add(Enum.valueOf(RequestMethod.class, obj.toString()));
        // }
        test.add(method);
        return ((List<RequestMethod>) test).toArray(new RequestMethod[0]);
    }

    public String[] params() {
        return invokeMethod("params");
    }

    public String[] headers() {
        return invokeMethod("headers");
    }

    public String[] consumes() {
        return invokeMethod("consumes");
    }

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
