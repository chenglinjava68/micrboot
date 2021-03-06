package com.zhuanglide.micrboot.mvc.resolver.param;

import com.zhuanglide.micrboot.http.HttpContextRequest;
import com.zhuanglide.micrboot.http.HttpContextResponse;
import com.zhuanglide.micrboot.mvc.ApiMethodParam;
import com.zhuanglide.micrboot.mvc.annotation.ApiParam;
import com.zhuanglide.micrboot.mvc.annotation.ApiPathVariable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiMethodParamBasicTypeResolver extends AbstractApiMethodParamResolver {
    public boolean support(ApiMethodParam apiMethodParam) {
        Annotation[] paramAnnotations = apiMethodParam.getParamAnnotations();
        if (null != paramAnnotations) {
            for (Annotation annotation : paramAnnotations) {
                if (annotation instanceof ApiParam) {
                    return true;
                }
            }
        }
        return false;
    }

    public Object getParamObject(ApiMethodParam apiMethodParam, HttpContextRequest request, HttpContextResponse response) throws Exception {
        if (support(apiMethodParam)) {
            Type type = apiMethodParam.getParamType();

            String paramName = apiMethodParam.getParamName();
            String paramDefaultValue = null;
            boolean required = true;

            Annotation[] paramAnnotations = apiMethodParam.getParamAnnotations();
            ApiParam apiParamAnnotation = null;

            if (paramAnnotations != null) {
                for (Annotation paramAnnotation : paramAnnotations) {
                    if (paramAnnotation instanceof ApiParam) {
                        apiParamAnnotation = (ApiParam) paramAnnotation;
                        paramName = apiParamAnnotation.value();
                        paramDefaultValue = apiParamAnnotation.defaultValue();
                        required = apiParamAnnotation.required();
                        break;
                    }
                }
            }

            String paramValue = request.getParameter(paramName);
            if (required && paramValue == null) {
                throw new IllegalArgumentException("param=" + paramName +" is required");
            }
            if (paramValue == null) {
                paramValue = paramDefaultValue;
            }

            if (apiParamAnnotation != null && apiParamAnnotation.validateRegx().length()>0) {
                Pattern pattern = Pattern.compile(apiParamAnnotation.validateRegx());
                Matcher matcher = pattern.matcher(String.valueOf(paramValue));
                if (!matcher.find()) {
                    throw new IllegalArgumentException("param=" + paramName +",value="+paramValue+" is illegal,pattern="+apiParamAnnotation.validateRegx());
                }
            }
            return convert(type, paramValue);
        }
        return null;
    }
}
