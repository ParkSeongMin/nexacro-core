package com.nexacro.spring.resolve;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.nexacro.spring.NexacroConstants;
import com.nexacro.spring.annotation.ParamDataSet;
import com.nexacro.spring.annotation.ParamVariable;
import com.nexacro.spring.data.NexacroFirstRowHandler;
import com.nexacro.spring.data.convert.ConvertDefinition;
import com.nexacro.spring.data.convert.NexacroConvertException;
import com.nexacro.spring.data.convert.NexacroConvertListener;
import com.nexacro.spring.data.convert.NexacroConverter;
import com.nexacro.spring.data.convert.NexacroConverterFactory;
import com.nexacro.spring.servlet.NexacroContext;
import com.nexacro.spring.servlet.NexacroContextHolder;
import com.nexacro.xapi.data.DataSet;
import com.nexacro.xapi.data.DataSetList;
import com.nexacro.xapi.data.PlatformData;
import com.nexacro.xapi.data.Variable;
import com.nexacro.xapi.data.VariableList;
import com.nexacro.xapi.tx.HttpPlatformRequest;
import com.nexacro.xapi.tx.HttpPlatformResponse;
import com.nexacro.xapi.tx.PlatformException;


/**
 * 
 * <pre>
 * Nexacro platform을 위한 <code>HandlerMethodArgumentResolver</code>이다.
 * 
 * 지원하는 형식은 다음과 같다.
 * 
 * ...
 * 
 * </pre>
 * 
 * @ClassName : NexacroMethodArgumnetResolver.java
 * @author Park SeongMin
 * @since 2015. 7. 27.
 * @version 1.0
 * @see NexacroHandlerMethodReturnValueHandler
 * @Modification Information
 * 
 * <pre>
 *     since          author              description
 *  ===========    =============    ===========================
 *  2015. 7. 27.     Park SeongMin     최초 생성
 * </pre>
 */
public class NexacroMethodArgumentResolver implements HandlerMethodArgumentResolver {

    private Logger logger = LoggerFactory.getLogger(NexacroMethodArgumentResolver.class);
    private Logger performanceLogger = LoggerFactory.getLogger(NexacroConstants.PERFORMANCE_LOGGER);

    // convert에서 사용가능한 event listener를 등록할 수 있어야 한다.
    private static final Map<Class<?>, Object> primitiveTypeDefaultValueMap = new HashMap<Class<?>, Object>(8);
    
    private List<NexacroConvertListener> convertListeners;
    
    static {
        primitiveTypeDefaultValueMap.put(byte.class,    0);
        primitiveTypeDefaultValueMap.put(short.class,   0);
        primitiveTypeDefaultValueMap.put(int.class,     0);
        primitiveTypeDefaultValueMap.put(long.class,    0l);
        primitiveTypeDefaultValueMap.put(float.class,   0.0f);
        primitiveTypeDefaultValueMap.put(double.class,  0.0d);
        primitiveTypeDefaultValueMap.put(char.class,    '\u0000');
        primitiveTypeDefaultValueMap.put(boolean.class, false);
    }
    
    public NexacroMethodArgumentResolver() {
        if(logger.isDebugEnabled()) {
            logger.debug("NexacroMethodArgumnetResolver() " + this);
        }
    }
    
    public void setConvertListeners(List<NexacroConvertListener> convertListeners) {
        this.convertListeners = convertListeners;
    }
    
    @Override
    public boolean supportsParameter(MethodParameter param) {
        if(isDefaultParameter(param)) {
            return true;
        } else if(isExtendedParameter(param)) {
            return true;
        }
        return false;
    }
    
    private boolean isDefaultParameter(MethodParameter param) {
        Class paramClass = param.getParameterType();
        if(PlatformData.class.equals(paramClass)) {
            return true;
        } else if(DataSetList.class.equals(paramClass)) {
            return true;
        } else if(VariableList.class.equals(paramClass)) {
            return true;
        } else if(HttpPlatformRequest.class.equals(paramClass)) {
            return true;
        } else if(HttpPlatformResponse.class.equals(paramClass)) {
            return true;
        } else if(NexacroFirstRowHandler.class.equals(paramClass)) {
            return true;
        } else {

        }
        return false;
    }

    private boolean isExtendedParameter(MethodParameter param) {
        if(param.getParameterAnnotation(ParamDataSet.class) != null) {
            return true;
        } else if(param.getParameterAnnotation(ParamVariable.class) != null) {
            return true;
        } else {
            // nothing.
        }
        return false;
    }
    
    @Override
    public Object resolveArgument(MethodParameter param, ModelAndViewContainer arg1, NativeWebRequest nativeWebRequest,
            WebDataBinderFactory arg3) throws Exception {
        
        NexacroContext nexacroCachedData = prepareResolveArguments(nativeWebRequest);
        
        StopWatch sw = new StopWatch(getClass().getSimpleName());
        sw.start("resolve argument (" +param.getParameterName()+ ")");
        
        Object obj = null;
        try {
            
            if(isDefaultParameter(param)) {
                obj = extractDefaultParameter(param, nexacroCachedData);
            } else {
                obj = extractExtendedParameter(param, nexacroCachedData);
            }
        } finally {
            sw.stop();
            if(performanceLogger.isTraceEnabled()) {
                performanceLogger.trace(sw.prettyPrint());
            }
        }
        
        obj = postResolveArguments(param, obj);
        
        return obj;
    }
    
    /**
     * <pre>
     * 데이터 변환을 수행하기 전 Request로 부터 Platform 데이터로의 변환을 수행한다.
     * </pre>
     *
     * @param nativeWebRequest
     * @return NexacroCachedData
     * @throws PlatformException
     */
    private NexacroContext prepareResolveArguments(NativeWebRequest nativeWebRequest) throws PlatformException {
        NexacroContext cache = (NexacroContext) parsePlatformRequestOrGetAttribute(nativeWebRequest);
        return cache;
    }
    
    private Object postResolveArguments(MethodParameter param, Object resolvedObject)  {
        
        if(resolvedObject != null) {
            return resolvedObject;
        }
        
        // primitive type의 경우 해당 데이터 타입으로 전달해야 한다.
        // null로 전달할 경우 IllegalArgumentException이 발생한다.
        Class<?> parameterType = param.getParameterType();    
        if(primitiveTypeDefaultValueMap.containsKey(parameterType)) {
            return primitiveTypeDefaultValueMap.get(parameterType);
        }
        
        return resolvedObject;
    }
    
    private Object extractDefaultParameter(MethodParameter param, NexacroContext nexacroCachedData) {
        
        Class<?> parameterType = param.getParameterType();
        
        if(PlatformData.class.equals(parameterType)) {
            return nexacroCachedData.getPlatformData();
        } else if(DataSetList.class.equals(parameterType)) {
            return nexacroCachedData.getPlatformData().getDataSetList();
        } else if(VariableList.class.equals(parameterType)) {
            return nexacroCachedData.getPlatformData().getVariableList();
        } else if(HttpPlatformRequest.class.equals(parameterType)) {
            return nexacroCachedData.getPlatformRequest();
        } else if(HttpPlatformResponse.class.equals(parameterType)) {
            return nexacroCachedData.getPlatformResponse();
        } else if(NexacroFirstRowHandler.class.equals(parameterType)) {
            return nexacroCachedData.getFirstRowHandler();
        } else {
            // nothing.
        }
        
        return null;
    }
    
    private Object extractExtendedParameter(MethodParameter param, NexacroContext nexacroCachedData) throws NexacroConvertException {
        
        if(param.getParameterAnnotation(ParamDataSet.class) != null) {
            return extractDataSetParameter(param, nexacroCachedData);
        } else if(param.getParameterAnnotation(ParamVariable.class) != null) {
            return extractVariableParameter(param, nexacroCachedData);
        } else {
            // nothing.
        }
        return null;
    }
    
    private Object extractDataSetParameter(MethodParameter param, NexacroContext nexacroCachedData) throws NexacroConvertException {
        
        Class<?> parameterType = param.getParameterType();
        ParamDataSet paramDataSet = param.getParameterAnnotation(ParamDataSet.class);
        
        String dsName = paramDataSet.name();
        DataSet dataSet = nexacroCachedData.getPlatformData().getDataSet(dsName);
        if(dataSet == null) {
            if(logger.isDebugEnabled()) {
                logger.debug(dsName + " is null.");
            }
            return null;
        }
        
        // return dataset
        if(DataSet.class.equals(parameterType)) {
            return dataSet;
        }
        
        // support only list parameter
        if(!List.class.equals(parameterType)) {
            throw new IllegalArgumentException(ParamDataSet.class.getSimpleName()+" annotation support List<?> and DataSet parameter.");
        }

        Class convertedGenericType = findGenericType(param);
        if(convertedGenericType == null) {
            // Generic이 null 일 경우에 Map으로 할지? 혹은 바로 오류를 내보낼지 처리 하도록 하자.
            throw new IllegalArgumentException(ParamDataSet.class.getSimpleName()+" annotation must be List<?>.");
        }
        
        
        ConvertDefinition definition = new ConvertDefinition(dsName);
        definition.setGenericType(convertedGenericType);

        Class<?> fromType = DataSet.class;
        Class<?> toType = parameterType;
        
        NexacroConverter<DataSet, List> converter = NexacroConverterFactory.getConverter(fromType, toType);
        if(converter == null) {
            throw new IllegalArgumentException("invalid @ParamDataSet. supported type={DataSet, List<?>}");
        }
        
        try {
            addConvertListeners(converter);
            return converter.convert(dataSet, definition);
        } finally {
            removeConvertListeners(converter);
        }
        
    }
    
    private Object extractVariableParameter(MethodParameter param, NexacroContext nexacroCachedData) throws NexacroConvertException {
        
        Class<?> parameterType = param.getParameterType();
        // support only list parameter
        
        ParamVariable paramVariable = param.getParameterAnnotation(ParamVariable.class);
        String varName = paramVariable.name();
        Variable variable = nexacroCachedData.getPlatformData().getVariable(varName);
        if(variable == null) {
            if(logger.isDebugEnabled()) {
                logger.debug(varName + " is null.");
            }
            return null;
            //throw new IllegalArgumentException("invalid @ParamVariable. ex)@ParamVariable(name=\"variableName\") Object var");
        }
        
        // reutrn variable
        if(Variable.class.equals(parameterType)) {
            return variable;
        }
        
        Class<?> fromType = Variable.class;
        Class<?> toType = parameterType;
        NexacroConverter<Variable, Object> converter = NexacroConverterFactory.getConverter(fromType, toType);
        if(converter == null) {
            throw new IllegalArgumentException("invalid @ParamVariable. supported type={Variable, Object, String, int, boolean, long, float, double, BigDecimal, Date, byte[]} ");
        }
        
        ConvertDefinition definition = new ConvertDefinition(varName);
        definition.setGenericType(parameterType);
        
        try {
            addConvertListeners(converter);
            return converter.convert(variable, definition);
        } finally {
            removeConvertListeners(converter);
        }
        
    }
    
    
    private Class findGenericType(MethodParameter param) {
        
        Type genericParameterType = param.getGenericParameterType();
        if (genericParameterType instanceof ParameterizedType) {
            Type[] types = ((ParameterizedType) genericParameterType).getActualTypeArguments();
            return (Class) types[0];
        }
        
        return null;
    }
    
    private Object getAttribute(NativeWebRequest nativeWebRequest, String attrName) {
        return nativeWebRequest.getAttribute(attrName, RequestAttributes.SCOPE_REQUEST);
    }

    private void setAttribute(NativeWebRequest nativeWebRequest, String attrName, Object obj) {
        nativeWebRequest.setAttribute(attrName, obj, RequestAttributes.SCOPE_REQUEST);
    }

    /**
     * NexacroPlatform 형식의 Cache 데이터를 반환 한다.
     *
     * @return
     * @throws PlatformException 
     */
    private NexacroContext parsePlatformRequestOrGetAttribute(NativeWebRequest nativeWebRequest) throws PlatformException {
        
        // when already parsed. ex - methodArgumentResolver or interceptor 
        NexacroContext cache = NexacroContextHolder.getNexacroContext();
        if(cache != null) {
            return  cache;
        }
        
        HttpServletRequest servletRequest = (HttpServletRequest) nativeWebRequest.getNativeRequest();
        HttpServletResponse servletResponse = (HttpServletResponse) nativeWebRequest.getNativeResponse();
        
        cache = NexacroContextHolder.getNexacroContext(servletRequest, servletResponse);
        return cache;
    }
 
    private void addConvertListeners(NexacroConverter converter) {
        if(this.convertListeners == null) {
            return;
        }
        for(NexacroConvertListener listener: this.convertListeners) {
            converter.addListener(listener);
        }
    }
    
    private void removeConvertListeners(NexacroConverter converter) {
        if(this.convertListeners == null) {
            return;
        }
        for(NexacroConvertListener listener: this.convertListeners) {
            converter.removeListener(listener);
        }
    }
    
}