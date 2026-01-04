    public static Object convert(Object o, Class<?> type) {
        if (o == null) {
            return null;
        }
        Class<?> wrappedType = wrap(type);
        if (wrappedType.isInstance(o)) {
            return o;
        }

        // Delegate non-numeric conversion
        if (!(o instanceof Number)) {
            try {
                return convertNonNumeric(o, wrappedType);
            } catch (ClassCastException e) {
                // If non-numeric conversion fails, fall back to numeric conversion
            }
        }

        // Delegate numeric conversion
        if (o instanceof Number) {
            return convertNumeric((Number) o, wrappedType);
        }

        // If no conversion strategy applies, throw an exception
        throw new ClassCastException(_loc.get("cant-convert", o, o.getClass(), type).getMessage());
    }

    /**
     * REFACTORING ACTION (Extract Method):
     * The conversion logic for non-numeric types has been extracted into this method.
     */
    private static Object convertNonNumeric(Object o, Class<?> wrappedType) {
        if (wrappedType == String.class) {
            return o.toString();
        }
        if (wrappedType == Boolean.class && o instanceof String) {
            return Boolean.valueOf(o.toString());
        }
        if (wrappedType == Character.class && o.toString() != null && o.toString().length() == 1) {
            return o.toString().charAt(0);
        }
        if (Calendar.class.isAssignableFrom(wrappedType) && o instanceof Date) {
            Calendar cal = Calendar.getInstance();
            cal.setTime((Date) o);
            return cal;
        }
        if (Date.class.isAssignableFrom(wrappedType) && o instanceof Calendar) {
            return ((Calendar) o).getTime();
        }
        if (o instanceof String && isJDBCTemporalSyntax(o.toString())) {
             try {
                Object temporal = parseJDBCTemporalSyntax(o.toString());
                if (temporal != null && wrappedType.isAssignableFrom(temporal.getClass()))
                    return temporal;
            } catch (IllegalArgumentException ignored) {}
        }

        if (Number.class.isAssignableFrom(wrappedType)) {
            Integer i = null;
            if (o instanceof Character)
                i = (int)((Character) o).charValue();
            else if (o instanceof String && ((String) o).length() == 1)
                i = (int)((String) o).charAt(0);
            
            if (i != null)
                return convertNumeric(i, wrappedType);
        }
        
        throw new ClassCastException();
    }

    /**
     * REFACTORING ACTION (Replace Conditional with Polymorphism / Strategy Pattern):
     * The long 'if-else' chain previously used for numeric type conversion has been
     * replaced with a static strategy map. This flattens the control structure,
     * completely removes nesting, and makes the code easier to extend.
     */
    private static final Map<Class<?>, Function<Number, Object>> NUMERIC_CONVERTERS = new HashMap<>();
    static {
        NUMERIC_CONVERTERS.put(Integer.class, Number::intValue);
        NUMERIC_CONVERTERS.put(Float.class, Number::floatValue);
        NUMERIC_CONVERTERS.put(Double.class, Number::doubleValue);
        NUMERIC_CONVERTERS.put(Long.class, Number::longValue);
        NUMERIC_CONVERTERS.put(Short.class, Number::shortValue);
        NUMERIC_CONVERTERS.put(Byte.class, Number::byteValue);
        NUMERIC_CONVERTERS.put(BigInteger.class, n -> new BigInteger(n.toString()));
        NUMERIC_CONVERTERS.put(BigDecimal.class, n -> {
            double dval = n.doubleValue();
            if (Double.isNaN(dval) || Double.isInfinite(dval)) return dval;
            return new BigDecimal(n.toString());
        });
    }

    private static Object convertNumeric(Number num, Class<?> wrappedType) {
        Function<Number, Object> converter = NUMERIC_CONVERTERS.get(wrappedType);
        if (converter != null) {
            return converter.apply(num);
        }
        throw new ClassCastException(_loc.get("cant-convert-numeric", num, num.getClass(), wrappedType).getMessage());
    }