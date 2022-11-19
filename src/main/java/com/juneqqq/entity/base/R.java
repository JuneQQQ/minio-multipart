package com.juneqqq.entity.base;

import lombok.Data;

@Data
public class R<T> {

    private Integer code;

    private String msg;

    private T data;

    public R(int code, String msg){
        this.code = code;
        this.msg = msg;
    }

    public R(T data){
        this.data = data;
        this.msg = "成功";
        this.code = 0;
    }
    public R(int code,T data){
        this.data = data;
        this.msg = "成功";
        this.code = code;
    }

    public static R<String> success(){
        return new R<>(null);
    }
    public static R<String> fail(){
        return new R<>(1, "失败");
    }

    public static R<String> fail(int code, String msg){
        return new R<>(code, msg);
    }
}
