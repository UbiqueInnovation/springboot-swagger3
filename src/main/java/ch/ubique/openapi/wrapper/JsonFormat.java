package ch.ubique.openapi.wrapper;

import com.fasterxml.jackson.annotation.OptBoolean;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;

public class JsonFormat extends MethodTranslator
        implements com.fasterxml.jackson.annotation.JsonFormat {
    public JsonFormat(Object obj) {
        this.obj = obj;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return (Class<? extends Annotation>) obj.getClass();
    }

    @Override
    public String pattern() {
        return invokeMethod("pattern");
    }

    @Override
    public Shape shape() {
        Object differentShape = invokeMethod("shape");
        String shapeName = "STRING";
        try {
            shapeName =
                    (String)
                            differentShape
                                    .getClass()
                                    .getMethod("name", null)
                                    .invoke(differentShape, null);
        } catch (IllegalAccessException
                | IllegalArgumentException
                | InvocationTargetException
                | NoSuchMethodException
                | SecurityException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return Enum.valueOf(Shape.class, shapeName);
    }

    public Object shapeWrapper() {
        return invokeMethod("shape");
    }

    @Override
    public String locale() {
        return invokeMethod("locale");
    }

    @Override
    public String timezone() {
        return invokeMethod("timezone");
    }

    @Override
    public OptBoolean lenient() {
        return invokeMethod("lenient");
    }

    @Override
    public Feature[] with() {
        return invokeMethod("feature");
    }

    @Override
    public Feature[] without() {
        return invokeMethod("without");
    }
}
