    /**
     * Convert the given value to the given type.
     */
    public static Object convert(Object o, Class<?> type) {
        if (o == null)
            return null;
        if (o.getClass() == type)
            return o;

        type = wrap(type);
        if (type.isAssignableFrom(o.getClass()))
            return o;

        // the non-numeric conversions we do are to string, or from
        // string/char to number, or calendar/date
        // String to Boolean
        // String to Integer
        boolean num = o instanceof Number;
        if (!num) {
            if (type == String.class)
                return o.toString();
            else if (type == Boolean.class && o instanceof String) 
                return Boolean.valueOf(o.toString());
            else if (type == Integer.class && o instanceof String)
                try {
                    return new Integer(o.toString());
                } catch (NumberFormatException e) {
                    throw new ClassCastException(_loc.get("cant-convert", o,
                        o.getClass(), type).getMessage());
                }
            else if (type == Character.class) {
                String str = o.toString();
                if (str != null && str.length() == 1)
                    return new Character(str.charAt(0));
            } else if (Calendar.class.isAssignableFrom(type) &&
                o instanceof Date) {
                Calendar cal = Calendar.getInstance();
                cal.setTime((Date) o);
                return cal;
            } else if (Date.class.isAssignableFrom(type) &&
                o instanceof Calendar) {
                return ((Calendar) o).getTime();
            } else if (Number.class.isAssignableFrom(type)) {
                Integer i = null;
                if (o instanceof Character)
                    i = Numbers.valueOf(((Character) o).charValue());
                else if (o instanceof String && ((String) o).length() == 1)
                    i = Numbers.valueOf(((String) o).charAt(0));

                if (i != null) {
                    if (type == Integer.class)
                        return i;
                    num = true;
                }
            } else if (o instanceof String && isJDBCTemporalSyntax(o.toString())) {
                try {
                    Object temporal = parseJDBCTemporalSyntax(o.toString());
                    if (temporal != null && type.isAssignableFrom(temporal.getClass()))
                        return temporal;
                } catch (IllegalArgumentException e) {
                    
                }
            }
        }
        if (!num)
            throw new ClassCastException(_loc.get("cant-convert", o,
                o.getClass(), type).getMessage());

        if (type == Integer.class) {
            return Numbers.valueOf(((Number) o).intValue());
        } else if (type == Float.class) {
            return new Float(((Number) o).floatValue());
        } else if (type == Double.class) {
            return new Double(((Number) o).doubleValue());
        } else if (type == Long.class) {
            return Numbers.valueOf(((Number) o).longValue());
        } else if (type == BigDecimal.class) {
            // the BigDecimal constructor doesn't handle the
            // "NaN" string version of Double.NaN and Float.NaN, nor
            // does it handle infinity; we need to instead use the Double
            // and Float versions, despite wanting to cast it to BigDecimal
            double dval = ((Number) o).doubleValue();
            if (Double.isNaN(dval) || Double.isInfinite(dval))
                return new Double(dval);

            float fval = ((Number) o).floatValue();
            if (Float.isNaN(fval) || Float.isInfinite(fval))
                return new Float(fval);

            return new BigDecimal(o.toString());
        } else if (type == BigInteger.class) {
            return new BigInteger(o.toString());
        } else if (type == Short.class) {
            return new Short(((Number) o).shortValue());
        } else if (type == Byte.class) {
            return new Byte(((Number) o).byteValue());
        } else {
            return Numbers.valueOf(((Number) o).intValue());
        }
    }