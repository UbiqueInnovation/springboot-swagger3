package ch.ubique.openapi.wrapper;

import java.lang.reflect.InvocationTargetException;

public class MethodTranslator {
    Object obj;

    <T> T invokeMethod(String function) {
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
