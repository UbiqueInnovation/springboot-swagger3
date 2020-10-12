package ch.ubique.openapi.wrapper;

import org.springframework.web.bind.annotation.RequestMethod;

public final class Mapping {
    Object obj;
    RequestMethod method;

    public Mapping(Object obj, RequestMethod method) {
        this.obj = obj;
        this.method = method;
    }

    public Object getObj() {
        return obj;
    }

    public RequestMethod getMethod() {
        return method;
    }
}
