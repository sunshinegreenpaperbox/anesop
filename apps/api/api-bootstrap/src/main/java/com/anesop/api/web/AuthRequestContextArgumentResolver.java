package com.anesop.api.web;

import com.anesop.m0.api.security.AuthRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 控制器参数解析：{@link AuthRequestContext} 由请求元数据（IP/UA/traceId）构造。
 *
 * <p>作为方法参数传入而不是在方法体内构造，使 {@code @Audited} 切面能直接读到审计上下文；
 * 同时让 M0 认证服务不依赖 Servlet API。
 */
@Component
public class AuthRequestContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthRequestContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return request == null ? AuthRequestContext.empty() : RequestContexts.from(request);
    }
}
