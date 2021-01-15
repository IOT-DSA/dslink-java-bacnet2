package bacnet;

import com.serotonin.bacnet4j.enums.DayOfWeek;
import com.serotonin.bacnet4j.enums.Month;
import com.serotonin.bacnet4j.npdu.NPCI.NetworkPriority;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.obj.PropertyTypeDefinition;
import com.serotonin.bacnet4j.obj.logBuffer.LogBuffer;
import com.serotonin.bacnet4j.type.AmbiguousValue;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.EncodedValue;
import com.serotonin.bacnet4j.type.constructed.BACnetArray;
import com.serotonin.bacnet4j.type.constructed.BaseType;
import com.serotonin.bacnet4j.type.constructed.ChannelValue;
import com.serotonin.bacnet4j.type.constructed.Choice;
import com.serotonin.bacnet4j.type.constructed.DaysOfWeek;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.LimitEnable;
import com.serotonin.bacnet4j.type.constructed.LogData;
import com.serotonin.bacnet4j.type.constructed.LogData.LogDataElement;
import com.serotonin.bacnet4j.type.constructed.LogStatus;
import com.serotonin.bacnet4j.type.constructed.ObjectTypesSupported;
import com.serotonin.bacnet4j.type.constructed.OptionalBinaryPV;
import com.serotonin.bacnet4j.type.constructed.OptionalCharacterString;
import com.serotonin.bacnet4j.type.constructed.OptionalReal;
import com.serotonin.bacnet4j.type.constructed.OptionalUnsigned;
import com.serotonin.bacnet4j.type.constructed.PriorityArray;
import com.serotonin.bacnet4j.type.constructed.PriorityValue;
import com.serotonin.bacnet4j.type.constructed.ResultFlags;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.ServicesSupported;
import com.serotonin.bacnet4j.type.constructed.SpecialEvent;
import com.serotonin.bacnet4j.type.constructed.StatusFlags;
import com.serotonin.bacnet4j.type.constructed.TimeValue;
import com.serotonin.bacnet4j.type.constructed.WeekNDay;
import com.serotonin.bacnet4j.type.constructed.WeekNDay.WeekOfMonth;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.BitString;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.Date;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.Primitive;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.Time;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.sero.ArrayUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.node.value.ValueUtils;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.reflections.Reflections;
import org.reflections.scanners.MethodParameterNamesScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TypeUtils {
	private static final int MAX_RECURSION_DEPTH = 20; //In case of infinite loops, generally should not be necessary
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TypeUtils.class);
	private static final Reflections reflections = new Reflections("com.serotonin.bacnet4j", new MethodParameterNamesScanner());

	public static Pair<ValueType, Value> parseEncodable(Encodable enc) {
		return parseEncodable(enc, MAX_RECURSION_DEPTH);
	}
	
	public static Pair<ValueType, Value> parseEncodable(Encodable enc, int maxDepth) {
		if (enc instanceof AmbiguousValue) {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		} else if (enc instanceof BaseType) {
			return parseBaseType((BaseType) enc, maxDepth);
		} else if (enc instanceof EncodedValue) {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		} else if (enc instanceof LogBuffer<?>) {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		} else if (enc instanceof Primitive) {
			return parsePrimitive((Primitive) enc);
		} else {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		}
	}
	
	public static Encodable formatEncodable(Class<? extends Encodable> clazz, Value val) {
		return formatEncodable(clazz, val, null, null);
	}
	
	@SuppressWarnings("unchecked")
	public static Encodable formatEncodable(Class<? extends Encodable> clazz, Value val, ObjectType ot, PropertyIdentifier pid) {
		boolean isSeq = false;
		if (clazz.equals(PriorityArray.class)) {
			isSeq = true;
		} else if (ot != null && pid != null) {
			PropertyTypeDefinition info = ObjectProperties.getObjectPropertyTypeDefinition(ot, pid).getPropertyTypeDefinition();
			isSeq = info.isCollection();
		} else if (val != null && getArray(val) != null) {
			isSeq = (!clazz.equals(BitString.class));
		}
		if (isSeq) {
			return formatSequenceOf(clazz, val, ot, pid);
		} else if (BaseType.class.isAssignableFrom(clazz)) {
			return formatBaseType((Class<? extends BaseType>) clazz, val, ot, pid);
		} else if (Primitive.class.isAssignableFrom(clazz)) {
			return formatPrimitive((Class<? extends Primitive>) clazz, val);
		} else {
			return null;
		}
	}

	/////////////////////////////////////////////////////////////////////////////////////////
	// Encodable Subtypes
	/////////////////////////////////////////////////////////////////////////////////////////

	@SuppressWarnings("unchecked")
	public static Pair<ValueType, Value> parseBaseType(BaseType enc, int maxDepth) {
		if (maxDepth == 0) {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		}
		maxDepth -= 1;
		if (enc instanceof SequenceOf<?>) {
			return Pair.of(ValueType.ARRAY, new Value(parseSequenceOf((SequenceOf<? extends Encodable>) enc, maxDepth)));
		} else if (enc instanceof ChannelValue) {
			Encodable encval = ((ChannelValue) enc).getValue();
			Value v = parseEncodable(encval, maxDepth).getRight();
			JsonObject jo = new JsonObject();
			jo.put("Value", ValueUtils.toObject(v));
			jo.put("_type", encval.getClass().getName());
			return Pair.of(ValueType.MAP, new Value(jo));
		} else if (enc instanceof PriorityValue) {
			return parseEncodable(((PriorityValue) enc).getValue());
		} else if (enc instanceof Choice) {
			return parseEncodable(((Choice) enc).getDatum(), maxDepth);
		} else if (enc.getClass().getSimpleName().startsWith("Optional")) {
			return parseOptional(enc);
		} else {
			return Pair.of(ValueType.MAP, new Value(parseNonSequenceConstructed(enc, maxDepth)));
		}
	}
	
	@SuppressWarnings("unchecked")
	public static BaseType formatBaseType(Class<? extends BaseType> clazz, Value val, ObjectType ot, PropertyIdentifier pid) {
		if (SequenceOf.class.isAssignableFrom(clazz)) {
			return formatSequenceOf(clazz, val, ot, pid);
		} else if (ChannelValue.class.isAssignableFrom(clazz)) {
			JsonObject jo = getMap(val);
			if (jo == null) {
			    return null;
			}
			Value v = ValueUtils.toValue(jo.get("Value"));
			try {
				Class<? extends Encodable> clz = (Class<? extends Encodable>) Class.forName(
						jo.get("_type"));
				for (Constructor<?> constr: ChannelValue.class.getConstructors()) {
					Class<?>[] params = constr.getParameterTypes();
					if (params.length == 1 && params[0].isAssignableFrom(clz)) {
						return (ChannelValue) constr.newInstance(formatEncodable(clz, v));
					}
				}
			} catch (InstantiationException | IllegalAccessException | InvocationTargetException | ClassNotFoundException | ClassCastException | NullPointerException ignored) {
			}
			return null;
		} else if (PriorityValue.class.isAssignableFrom(clazz)) {
			if (val == null || ot == null) {
				return new PriorityValue(Null.instance);
			}
			Class<? extends Encodable> clz = ObjectProperties.getObjectPropertyTypeDefinition(ot, PropertyIdentifier.presentValue).getPropertyTypeDefinition().getClazz();
			return new PriorityValue(formatEncodable(clz, val));
		} else if (clazz.getSimpleName().startsWith("Optional")) {
			return formatOptional(clazz, val);
		} else if (TimeValue.class.isAssignableFrom(clazz)) {
		    return formatTimeValue(val);
		} else {
			return formatNonSequenceConstructed(clazz, val, ot);
		}
	}
	
	public static Pair<ValueType, Value> parsePrimitive(Primitive enc) {
		if (enc instanceof BitString) {
			return parseBitString((BitString) enc);
		} else if (enc instanceof com.serotonin.bacnet4j.type.primitive.Boolean) {
			boolean b = ((com.serotonin.bacnet4j.type.primitive.Boolean) enc).booleanValue();
			return Pair.of(ValueType.BOOL, new Value(b));
		} else if (enc instanceof CharacterString) {
			return Pair.of(ValueType.STRING, new Value(((CharacterString) enc).getValue()));
		} else if (enc instanceof Date) {
			return Pair.of(ValueType.MAP, new Value(parseDate((Date) enc)));
		} else if (enc instanceof com.serotonin.bacnet4j.type.primitive.Double) {
			double d = ((com.serotonin.bacnet4j.type.primitive.Double) enc).doubleValue();
			return Pair.of(ValueType.NUMBER, new Value(d));
		} else if (enc instanceof Enumerated) {
			return parseEnumerated((Enumerated) enc);
		} else if (enc instanceof Null) {
			return Pair.of(ValueType.STRING, null);
		} else if (enc instanceof ObjectIdentifier) {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		} else if (enc instanceof OctetString) {
			return parseOctetString((OctetString) enc);
		} else if (enc instanceof Real) {
			return Pair.of(ValueType.NUMBER, new Value(((Real) enc).floatValue()));
		} else if (enc instanceof SignedInteger) {
			return Pair.of(ValueType.NUMBER, new Value(((SignedInteger) enc).bigIntegerValue()));
		} else if (enc instanceof Time) {
			return Pair.of(ValueType.STRING, new Value(parseTime((Time) enc)));
		} else if (enc instanceof UnsignedInteger) {
			return Pair.of(ValueType.NUMBER, new Value(((UnsignedInteger) enc).bigIntegerValue()));
		} else {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		}
	}
	
	@SuppressWarnings("unchecked")
	public static Primitive formatPrimitive(Class<? extends Primitive> clazz, Value val) {
		if (BitString.class.isAssignableFrom(clazz)) {
			return formatBitString((Class<? extends BitString>) clazz, val);
		} else if (com.serotonin.bacnet4j.type.primitive.Boolean.class.isAssignableFrom(clazz)) {
			return com.serotonin.bacnet4j.type.primitive.Boolean.valueOf(val.getBool());
		} else if (CharacterString.class.isAssignableFrom(clazz)) {
			return new CharacterString(val.getString());
		} else if (Date.class.isAssignableFrom(clazz)) {
			return formatDate(val);
		} else if (com.serotonin.bacnet4j.type.primitive.Double.class.isAssignableFrom(clazz)) {
			return new com.serotonin.bacnet4j.type.primitive.Double(val.getNumber().doubleValue());
		} else if (Enumerated.class.isAssignableFrom(clazz)) {
			return formatEnumerated((Class<? extends Enumerated>) clazz, val);
		} else if (Null.class.isAssignableFrom(clazz)) {
			return  Null.instance;
		} else if (ObjectIdentifier.class.isAssignableFrom(clazz)) {
			String[] arr = val.getString().split(" ");
			int instnum = Integer.parseInt(arr[arr.length - 1]);
			String type = arr[0];
			return new ObjectIdentifier(ObjectType.forName(type), instnum);
		} else if (OctetString.class.isAssignableFrom(clazz)) {
			return formatOctetString((Class<? extends OctetString>) clazz, val);
		} else if (Real.class.isAssignableFrom(clazz)) {
			return new Real(val.getNumber().floatValue());
		} else if (SignedInteger.class.isAssignableFrom(clazz)) {
			return new SignedInteger(val.getNumber().intValue());
		} else if (Time.class.isAssignableFrom(clazz)) {
			return formatTime(val);
		} else if (UnsignedInteger.class.isAssignableFrom(clazz)) {
			try {
				return clazz.getConstructor(int.class).newInstance(val.getNumber().intValue());
			} catch (NullPointerException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
				return null;
			}
		} else {
			return null;
		}
	}
	
	private static TimeValue formatTimeValue(Value val) {
	    JsonObject jobj = getMap(val);
        if (jobj == null) {
            return null;
        }
        Time time = formatTime(ValueUtils.toValue(jobj.get("Time")));
        String simpleClassName = jobj.get("Type");
        Class<? extends Primitive> clazz = null;
        if (simpleClassName != null) {  
            try {
                clazz = (Class<? extends Primitive>) Class.forName("com.serotonin.bacnet4j.type.primitive." + simpleClassName);
            } catch (Exception e) {
                try {
                    clazz = (Class<? extends Primitive>) Class.forName("com.serotonin.bacnet4j.type.enumerated." + simpleClassName);
                } catch (Exception e1) {
                    try {
                        clazz = (Class<? extends Primitive>) Class.forName("com.serotonin.bacnet4j.type.constructed." + simpleClassName);
                    } catch (Exception e2) {
                        clazz = null;
                    }
                }
            }
        }
        Value valueval = ValueUtils.toValue(jobj.get("Value"));
        if (clazz == null) {
            if (valueval == null) {
                clazz = Null.class;
            } else {
                ValueType vt = valueval.getType();
                if (ValueType.BOOL.compare(vt)) {
                    clazz = com.serotonin.bacnet4j.type.primitive.Boolean.class;
                } else if (ValueType.NUMBER.compare(vt)) {
                    clazz = com.serotonin.bacnet4j.type.primitive.Double.class;
                } else {
                    clazz = CharacterString.class;
                }
            }
        }

        Primitive value = formatPrimitive(clazz, valueval);
        return new TimeValue(time, value);
	}
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// BaseType Subtypes
	/////////////////////////////////////////////////////////////////////////////////////////
	
	public static JsonArray parseSequenceOf(SequenceOf<? extends Encodable> enc, int maxDepth) {
		JsonArray jarr = new JsonArray();
		if (enc instanceof PriorityArray) {
			jarr.add("");
		}
		for (Encodable item: enc) {
			Pair<ValueType, Value> p = parseEncodable(item, maxDepth);
			ValueType vt = p.getLeft();
			Value v = p.getRight();
			if (v == null) {
				jarr.add(null);
			} else if (vt.compare(ValueType.ARRAY)) {
				jarr.add(getArray(v));
			} else if (vt.compare(ValueType.BOOL)) {
				jarr.add(v.getBool());
			} else if (vt.compare(ValueType.MAP)) {
				jarr.add(getMap(v));
			} else if (vt.compare(ValueType.NUMBER)) {
				jarr.add(v.getNumber());
			} else {
				jarr.add(v.getString());
			}
		}
		return jarr;
	}
	
	public static SequenceOf<? extends Encodable> formatSequenceOf(Class<? extends Encodable> clazz, Value val, ObjectType ot, PropertyIdentifier pid) {
		if (SequenceOf.class.isAssignableFrom(clazz)) {
			if (PriorityArray.class.isAssignableFrom(clazz)) {
				PriorityArray pa = new PriorityArray();
				JsonArray ja = getArray(val);
				if (ja != null) {
					if (ja.size() == 17) {
						ja.remove(0);
					}
					for (int i=0; i<16; i++) {
						if (ja.size() > i && ja.get(i) != null) {
							pa.put(i+1, formatBaseType(PriorityValue.class, ValueUtils.toValue(ja.get(i)), ot, pid));
						}
					}
				}
				return pa;
			} else {
				return null;
			}
		} else {
			PropertyTypeDefinition info = ObjectProperties.getObjectPropertyTypeDefinition(ot, pid).getPropertyTypeDefinition();
			if (info.isArray()) {
				return formatArray(clazz, val);
			} else if (info.isList()) {
				return formatList(clazz, val);
			} else {
				return null;
			}
		}
	}
	
	private static BACnetArray<Encodable> formatArray(Class<? extends Encodable> clazz, Value val) {
	    JsonArray ja = getArray(val);
        if (ja != null) {
            int size = ja.size();
            BACnetArray<Encodable> arr = new BACnetArray<>(size, null);
            for (int i=1; i <= size; i++) {
                arr.setBase1(i, formatEncodable(clazz, ValueUtils.toValue(ja.get(i - 1))));
            }
            return arr;
        } else {
            return new BACnetArray<>();
        }
	}
	
	private static SequenceOf<? extends Encodable> formatList(Class<? extends Encodable> clazz, Value val) {
		SequenceOf<Encodable> seq = new SequenceOf<>();
		JsonArray ja = getArray(val);
		if (ja != null) {
			for (Object o: ja) {
				seq.add(formatEncodable(clazz, ValueUtils.toValue(o)));
			}
		}
		return seq;
	}
	
	public static Pair<ValueType, Value> parseOptional(BaseType enc) {
		if (enc instanceof OptionalBinaryPV) {
			try {
				BinaryPV bpv = ((OptionalBinaryPV) enc).getBinaryPVValue();
				return parsePrimitive(bpv);
			} catch (Exception e) {
				return Pair.of(ValueType.STRING, null);
			}
		} else if (enc instanceof OptionalCharacterString) {
			try {
				CharacterString cs = ((OptionalCharacterString) enc).getCharacterStringValue();
				return parsePrimitive(cs);
			} catch (Exception e) {
				return Pair.of(ValueType.STRING, null);
			}
		} else if (enc instanceof OptionalReal) {
			try {
				Real r = ((OptionalReal) enc).getRealValue();
				return parsePrimitive(r);
			} catch (Exception e) {
				return Pair.of(ValueType.NUMBER, null);
			}
		} else if (enc instanceof OptionalUnsigned) {
			try {
				UnsignedInteger u = ((OptionalUnsigned) enc).getUnsignedIntegerValue();
				return parsePrimitive(u);
			} catch (Exception e) {
				return Pair.of(ValueType.NUMBER, null);
			}
		}
		return Pair.of(ValueType.STRING, null);
	}
	
	public static BaseType formatOptional(Class<? extends BaseType> clazz, Value val) {
		String s = null;
		Boolean b = null;
		Number n = null;
		if (val != null) {
			s = val.getString();
			b = val.getBool();
			n = val.getNumber();
		}
		if (clazz.equals(OptionalBinaryPV.class)) {
			if (s != null) {
				try {
					return new OptionalBinaryPV(BinaryPV.forName(s));
				} catch (Exception ignored) {
					
				}
			} else if (b != null) {
				return new OptionalBinaryPV(b ? BinaryPV.active : BinaryPV.inactive);
			}
			return new OptionalBinaryPV();
		} else if (clazz.equals(OptionalCharacterString.class)) {
			if (s != null) {
				return new OptionalCharacterString(new CharacterString(s));
			}
			return new OptionalCharacterString();
		} else if (clazz.equals(OptionalReal.class)) {
			if (n != null) {
				return new OptionalReal(n.floatValue());
			}
			return new OptionalReal();
		} else if (clazz.equals(OptionalUnsigned.class)) {
			if (n != null && n.intValue() >= 0) {
				return new OptionalUnsigned(n.intValue());
			}
			return new OptionalUnsigned();
		} else {
			return null;
		}
	}
	
	@SuppressWarnings("unchecked")
	public static JsonObject parseNonSequenceConstructed(BaseType enc, int maxDepth) {
		Class<? extends BaseType> clazz = enc.getClass();
		Set<Method> gets = new HashSet<>();
		Map<String, Method> ises = new HashMap<>();
		for (Method method: clazz.getMethods()) {
			String name = method.getName();
			if (!method.getDeclaringClass().equals(Object.class) && method.getParameterCount() == 0) {
				if (name.startsWith("get")) {
					gets.add(method);
				} else if (name.startsWith("is") && method.getReturnType().equals(boolean.class)) {
					ises.put(name.substring(2), method);
				}
			}
		}
		
		if (enc instanceof SpecialEvent) {
		    ises.remove("ListOfTimeValues");
		    ises.remove("EventPriority");
		}
		
		JsonObject jobj = new JsonObject();
		
		for (Method getMethod: gets) {
			String key = getMethod.getName().substring(3);
			Method isMethod = ises.get(key);
			if (isMethod == null && key.equals("Data")) {
				isMethod = ises.get("LogData");
			}
			if (isMethod != null) {
				boolean isWhatever = false;
				try {
					isWhatever = (Boolean) isMethod.invoke(enc);
				} catch (Exception e) {
					LOGGER.debug("", e);
				} 
				if (!isWhatever) {
					continue;
				}
			}
			try {
				Object o = getMethod.invoke(enc);
				if (enc.equals(o)) {
					continue;
				}
				if (o instanceof Encodable) {
					Pair<ValueType, Value> p = parseEncodable((Encodable) o, maxDepth);
					ValueType vt = p.getLeft();
					Value v = p.getRight();
					if (v == null) {
						jobj.put(key, null);
					} else if (vt.compare(ValueType.ARRAY)) {
						jobj.put(key, getArray(v));
					} else if (vt.compare(ValueType.BOOL)) {
						jobj.put(key, v.getBool());
					} else if (vt.compare(ValueType.MAP)) {
						jobj.put(key, getMap(v));
					} else if (vt.compare(ValueType.NUMBER)) {
						jobj.put(key, v.getNumber());
					} else {
						jobj.put(key, v.getString());
					}
				} else if (o instanceof List) {
					jobj.put(key, listToJsonArray((List<? extends BaseType>) o, maxDepth));
				} else if (o instanceof NetworkPriority) {
					jobj.put(key, o.toString());
				} else if (o instanceof Number) {
					jobj.put(key, o);
				} else if (o instanceof String) {
					jobj.put(key, o);
				}
			} catch (Exception e) {
				LOGGER.debug("", e);
			}
		}
		if (enc instanceof TimeValue) {
		    Primitive value = ((TimeValue) enc).getValue();
		    if (value == null) {
		        value = new Null();
		    }
		    jobj.put("Type", value.getClass().getSimpleName());
		}
		return jobj;
	}
	
	@SuppressWarnings("unchecked")
	public static BaseType formatNonSequenceConstructed(Class<? extends BaseType> clazz, Value val, ObjectType ot) {
		JsonObject jobj = getMap(val);
		if (jobj == null) {
			return null;
		}
		if (LogData.class.equals(clazz)) {
			Object o = jobj.get("Datum");
			if (o == null) {
				return null;
			}
			Value v = ValueUtils.toValue(o);
			if (v == null) {
				return null;
			}
			if (getMap(v) != null) {
				return new LogData((LogStatus) formatBitString(LogStatus.class, v));
			} else if (getArray(v) != null) {
				return new LogData((SequenceOf<LogDataElement>) formatList(LogDataElement.class, v));
			} else if (v.getNumber() != null) {
				return new LogData((Real) formatPrimitive(Real.class, v));
			} else {
				return null;
			}
		}
		Set<String> keyset = new HashSet<>();
		for (Entry<String, Object> entry: jobj) {
			if (entry.getValue() != null) {
				keyset.add(entry.getKey());
			}
		}
		
		Map<String, Method> sets = new HashMap<>();
		for (Method method: clazz.getMethods()) {
			String name = method.getName();
			if (!method.getDeclaringClass().equals(Object.class) && method.getParameterCount() == 1 && name.startsWith("set")) {
				sets.put(name.substring(3), method);
			}
		}
		
		Constructor<?> bestConstr = null;
		List<String> bestParamList = null;
		int bestMatchCount = -1;
//		Set<Constructor<?>> constrsWithOneBadParam = new HashSet<Constructor<?>>();
		for (Constructor<?> constr: clazz.getConstructors()) {
			List<String> paramList = new ArrayList<>();
			Set<String> keysetcpy = new HashSet<>(keyset);
			int badParamCount = 0;
			for (String paramName: reflections.getConstructorParamNames(constr)) {
//			    LOGGER.info(param.getName());
				String name = findIgnoreCase(keysetcpy, paramName);
				if (name == null) {
					badParamCount += 1;
					if (badParamCount > 1) {
						break;
					}
				}
				paramList.add(name);
				keysetcpy.remove(name);
			}
			if (badParamCount == 0) {
				for (String key: sets.keySet()) {
					String name = findIgnoreCase(keysetcpy, key);
					if (name != null) {
						paramList.add(name);
						keysetcpy.remove(name);
					}
				}
				int matchCount = keyset.size() - keysetcpy.size();
				if (matchCount > bestMatchCount) {
					bestMatchCount = matchCount;
					bestConstr = constr;
					bestParamList = paramList;
				}
//			} else if (badParamCount == 1) {
//				constrsWithOneBadParam.add(constr);
			}
		}
		
		if (bestConstr != null) {
			Object[] params = new Object[bestConstr.getParameterCount()];
			BaseType instance = null;
			Type[] paramTypes = bestConstr.getGenericParameterTypes();
			for (int i=0; i<bestParamList.size(); i++) {
				if (i < params.length) {
					params[i] = formatSomething(paramTypes[i], ValueUtils.toValue(jobj.get(bestParamList.get(i))));
					if (i == params.length - 1) {
						try {
							instance = (BaseType) bestConstr.newInstance(params);
						} catch (Exception e) {
							return null;
						}
					}
				} else if (instance != null) {
					Method setMeth = sets.get(bestParamList.get(i));
					Object param = formatSomething(setMeth.getParameterTypes()[0], ValueUtils.toValue(jobj.get(bestParamList.get(i))));
					try {
						setMeth.invoke(instance, param);
					} catch (Exception ignored) {
					}
				}
			}
			return instance;
		} else {
			return null;
		}
	}
	
	@SuppressWarnings("unchecked")
	private static Object formatSomething(Type type, Value val) {
	    Class<?> clazz;
	    Class<? extends Encodable> paramClazz = null;
	    if (type instanceof ParameterizedType) {
	        clazz = (Class<?>) ((ParameterizedType) type).getRawType();
	        Type[] typeargs = ((ParameterizedType) type).getActualTypeArguments();
	        if (typeargs.length > 0) {
	            paramClazz = (Class<? extends Encodable>) typeargs[0];
	        }
	    } else {
	        clazz = (Class<?>) type;
	    }
		Number n = val.getNumber();
		Boolean b = val.getBool();
		String s = val.getString();
		if (clazz.equals(BACnetArray.class)) {
            return formatArray(paramClazz, val);
        } else if (clazz.equals(SequenceOf.class)) {
            return formatList(paramClazz, val);
        } else if (Encodable.class.isAssignableFrom(clazz)) {
			return formatEncodable((Class<? extends Encodable>) clazz, val);
		} else if (clazz.equals(boolean.class) || clazz.equals(Boolean.class)) {
			return b;
		} else if (clazz.equals(byte.class) || clazz.equals(Byte.class)) {
			return n != null ? n.byteValue() : null;
		} else if (clazz.equals(short.class) || clazz.equals(Short.class)) {
			return n != null ? n.shortValue() : null;
		} else if (clazz.equals(int.class) || clazz.equals(Integer.class)) {
			return n != null ? n.intValue() : null;
		} else if (clazz.equals(long.class) || clazz.equals(Long.class)) {
			return n != null ? n.longValue() : null;
		} else if (clazz.equals(float.class) || clazz.equals(Float.class)) {
			return n != null ? n.floatValue() : null;
		} else if (clazz.equals(double.class) || clazz.equals(Double.class)) {
			return n != null ? n.doubleValue() : null;
		} else if (clazz.equals(BigInteger.class)) {
			return n != null ? BigInteger.valueOf(n.longValue()) : null;
		} else if (clazz.equals(String.class)) {
			return s;
		} else {
			return null;
		}
	}
	
	
	private static String findIgnoreCase(Set<String> set, String string) {
		for (String element: set) {
			if (element.equalsIgnoreCase(string)) {
				return element;
			}
		}
		return null;
	}
	
	public static JsonArray listToJsonArray(List<? extends BaseType> list, int maxDepth) {
		JsonArray jarr = new JsonArray();
		for (BaseType item: list) {
			jarr.add(parseNonSequenceConstructed(item, maxDepth));
		}
		return jarr;
	}


	/////////////////////////////////////////////////////////////////////////////////////////
	// Primitive Subtypes
	/////////////////////////////////////////////////////////////////////////////////////////

	public static Pair<ValueType, Value> parseBitString(BitString enc) {
		List<String> labels = getBitStringLabels(enc.getClass());
		if (labels != null) {
			JsonObject jobj = new JsonObject();
			for (int i = 0; i < labels.size(); i++) {
				String l = labels.get(i);
				boolean b = enc.getArrayValue(i);
				jobj.put(l, b);
			}
			return Pair.of(ValueType.MAP, new Value(jobj));
		} else {
			JsonArray jarr = new JsonArray();
			for (boolean b : enc.getValue()) {
				jarr.add(b);
			}
			return Pair.of(ValueType.ARRAY, new Value(jarr));
		}
	}
	
	public static BitString formatBitString(Class<? extends BitString> clazz, Value val) {
		if (clazz.equals(BitString.class)) {
			JsonArray jarr = getArray(val);
			if (jarr == null) {
			    return null;
			}
			boolean[] params = new boolean[jarr.size()];
			for (int i = 0; i < params.length; i++) {
				params[i] = jarr.get(i);
			}
			return new BitString(params);
		} else if (DaysOfWeek.class.isAssignableFrom(clazz)) {
			return formatDaysOfWeek(val);
		} else if (ObjectTypesSupported.class.isAssignableFrom(clazz)) {
			return formatObjectTypesSupported(val);
		} else if (ServicesSupported.class.isAssignableFrom(clazz)) {
			return formatServicesSupported(val);
		} else {
			List<String> labels = getBitStringLabels(clazz);
			if (labels == null) {
				return null;
			}
			Class<?>[] parameterTypes = new Class<?>[labels.size()];
			Boolean[] params = new Boolean[labels.size()];
			JsonObject jobj = val.getMap();
			JsonArray jarr = val.getArray();
			if (jobj == null && jarr == null) {
				String s = val.getString();
				try {
					jobj = new JsonObject(s);
				} catch (Exception e) {
					jobj = null;
				}
				try {
					jarr = new JsonArray(s);
				} catch (Exception e) {
					jarr = null;
				}
			}
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterTypes[i] = boolean.class;
				if (jobj != null) {
					params[i] = jobj.get(labels.get(i), false);
				} else if (jarr != null && jarr.size() > i) {
					params[i] = jarr.get(i);
				} 
			}
			try {
				Constructor<? extends BitString> constr = clazz.getConstructor(parameterTypes);
				return constr.newInstance((Object[]) params);
			} catch (Exception e) {
				return null;
			}
		}
	}

	public static List<String> getBitStringLabels(Class<? extends BitString> clazz) {
		if (DaysOfWeek.class.isAssignableFrom(clazz)) {
			return Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
		} else if (EventTransitionBits.class.isAssignableFrom(clazz)) {
			return Arrays.asList("To Offnormal", "To Fault", "To Normal");
		} else if (LimitEnable.class.isAssignableFrom(clazz)) {
			return Arrays.asList("Low Limit Enable", "High Limit Enable");
		} else if (LogStatus.class.isAssignableFrom(clazz)) {
			return Arrays.asList("Log Disabled", "Buffer Purged", "Log Interrupted");
		} else if (ObjectTypesSupported.class.isAssignableFrom(clazz)) {
			return Utils.getObjectTypeList();
		} else if (ResultFlags.class.isAssignableFrom(clazz)) {
			return Arrays.asList("First Item", "Last Item", "More Items");
		} else if (ServicesSupported.class.isAssignableFrom(clazz)) {
			return Arrays.asList("AcknowledgeAlarm", "ConfirmedCovNotification", "ConfirmedEventNotification",
					"GetAlarmSummary", "GetEnrollmentSummary", "SubscribeCov", "AtomicReadFile", "AtomicWriteFile",
					"AddListElement", "RemoveListElement", "CreateObject", "DeleteObject", "ReadProperty",
					"ReadPropertyConditional", "ReadPropertyMultiple", "WriteProperty", "WritePropertyMultiple",
					"DeviceCommunicationControl", "ConfirmedPrivateTransfer", "ConfirmedTextMessage",
					"ReinitializeDevice", "VtOpen", "VtClose", "VtData", "Authenticate", "RequestKey", "IAm", "IHave",
					"UnconfirmedCovNotification", "UnconfirmedEventNotification", "UnconfirmedPrivateTransfer",
					"UnconfirmedTextMessage", "TimeSynchronization", "WhoHas", "WhoIs", "ReadRange",
					"UtcTimeSynchronization", "LifeSafetyOperation", "SubscribeCovProperty", "GetEventInformation",
					"WriteGroup", "SubscribeCovPropertyMultiple", "ConfirmedCovNotificationMultiple",
					"UnconfirmedCovNotificationMultiple");
		} else if (StatusFlags.class.isAssignableFrom(clazz)) {
			return Arrays.asList("InAlarm", "Fault", "Overridden", "OutOfService");
		} else {
			return null;
		}
	}
	
	public static JsonObject parseDate(Date enc) {
		JsonObject dateObj = new JsonObject();
		if (enc.getYear() != Date.UNSPECIFIED_YEAR) {
			dateObj.put("Year", enc.getCenturyYear());
		}
		if (enc.getMonth() != Month.UNSPECIFIED) {
			dateObj.put("Month", enc.getMonth().toString());
		}
		if (enc.getDay() != Date.UNSPECIFIED_DAY) {
			dateObj.put("Day", enc.getDay());
		}
		if (enc.getDayOfWeek() != DayOfWeek.UNSPECIFIED) {
			dateObj.put("Day of Week", enc.getDayOfWeek().toString());
		}
		return dateObj;
	}
	
	public static Date formatDate(Value val) {
		JsonObject jobj = getMap(val);
		if (jobj == null) {
			return Date.UNSPECIFIED;
		}
		int yr = jobj.get("Year", -1);
		Month mn = Month.valueOf(jobj.get("Month", Month.UNSPECIFIED.toString()));
		int dy = jobj.get("Day", -1);
		DayOfWeek dw = DayOfWeek.valueOf(jobj.get("Day of Week", DayOfWeek.UNSPECIFIED.toString()));
		return new Date(yr, mn, dy, dw);
	}
	
	public static Pair<ValueType, Value> parseEnumerated(Enumerated enc) {
		Class<? extends Enumerated> clazz = enc.getClass();
		ValueType vt = ValueType.STRING;
		if (!Enumerated.class.equals(clazz)) {
			List<String> states = Utils.getEnumeratedStateList(clazz);
			if (states != null) {
				vt = ValueType.makeEnum(states);
			}
		} else {
			return Pair.of(ValueType.NUMBER, new Value(enc.intValue()));
		}
		Value v = new Value(enc.toString());
		return Pair.of(vt, v);
	}
	
	public static Enumerated formatEnumerated(Class<? extends Enumerated> clazz, Value val) {
		Number n = val.getNumber();
		Boolean b = val.getBool();
		String s = val.getString();
		if (n == null && b != null) {
			n = b ? 1 : 0;
		}
		if (n != null) {
			try {
				Method meth = clazz.getMethod("forId", int.class);
				if (Modifier.isStatic(meth.getModifiers()) && clazz.equals(meth.getReturnType())) {
					return (Enumerated) meth.invoke(null, n.intValue());
				}
			} catch (ClassCastException | NullPointerException | NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ignored) {
			}
		} else if (s != null) {
			try {
				Method meth = clazz.getMethod("forName", String.class);
				if (Modifier.isStatic(meth.getModifiers()) && clazz.equals(meth.getReturnType())) {
					return (Enumerated) meth.invoke(null, s);
				}
			} catch (ClassCastException | NullPointerException | NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ignored) {
			}
		}
		return new Enumerated(val.getNumber().intValue());
		
	}

	public static Pair<ValueType, Value> parseOctetString(OctetString enc) {
		if (enc instanceof WeekNDay) {
			return Pair.of(ValueType.MAP, new Value(parseWeekNDay((WeekNDay) enc)));
		} else {
			String val = ArrayUtils.toPlainHexString(enc.getBytes());
			return Pair.of(ValueType.STRING, new Value(val));
		}
	}
	
	public static OctetString formatOctetString(Class<? extends OctetString> clazz, Value val) {
		if (WeekNDay.class.isAssignableFrom(clazz)) {
			return formatWeekNDay(val);
		} else {
			return new OctetString(ArrayUtils.fromPlainHexString(val.getString()));
		}
	}
	
	public static String parseTime(Time enc) {
		String str = "";
		String part = (enc.isHourUnspecified()) ? "???" : ("00" + Integer.toString(enc.getHour()));
		str += part.substring(part.length() - 2) + ":";
		part = (enc.isMinuteUnspecified()) ? "???" : ("00" + Integer.toString(enc.getMinute()));
		str += part.substring(part.length() - 2) + ":";
		part = (enc.isSecondUnspecified()) ? "???" : ("00" + Integer.toString(enc.getSecond()));
		str += part.substring(part.length() - 2) + ".";
		part = (enc.isHundredthUnspecified()) ? "????" : (Integer.toString(enc.getHundredth()) + "000");
		str += part.substring(0, 3);
		return str;
	}
	
	public static Time formatTime(Value val) {
		String str = val.getString();
		if (str == null) {
			return new Time(255, 255, 255, 255);
		}
		int hr;
		int min;
		int sec;
		int hund;
		try {
			hr = Integer.parseInt(str.substring(0, 2));
		} catch (NumberFormatException e) {
			hr = 255;
		}
		try {
			min = Integer.parseInt(str.substring(3, 5));
		} catch (NumberFormatException e) {
			min = 255;
		}
		try {
			sec = Integer.parseInt(str.substring(6, 8));
		} catch (NumberFormatException e) {
			sec = 255;
		}
		try {
			hund = Integer.parseInt(str.substring(9, 11));
		} catch (NumberFormatException e) {
			hund = 255;
		}
		return new Time(hr, min, sec, hund);
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// BitString Subtypes 
	/////////////////////////////////////////////////////////////////////////////////////////
	
	
	public static DaysOfWeek formatDaysOfWeek(Value val) {
		try {
			return (DaysOfWeek) formatDowOrSs(val, DaysOfWeek.class);
		} catch (Exception e) {
			return null;
		}
	}
	
	public static ServicesSupported formatServicesSupported(Value val) {
		try {
			return (ServicesSupported) formatDowOrSs(val, ServicesSupported.class);
		} catch (Exception e) {
			return null;
		}
	}
	
	private static BitString formatDowOrSs(Value val, Class<? extends BitString> clazz) throws InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
		JsonObject jobj = getMap(val);
		if (jobj == null) {
			return null;
		}
		List<String> labels = getBitStringLabels(clazz);
		BitString obj = clazz.getConstructor().newInstance();
		if (labels == null) {
		    return obj;
		}
		for (String label: labels) {
			try {
				clazz.getMethod("set" + label, boolean.class).invoke(obj, jobj.get(label, false));
			} catch (Exception e) {
				LOGGER.debug("", e);
			}
		}
		return obj;
	}
	
	public static ObjectTypesSupported formatObjectTypesSupported(Value val) {
		JsonObject jobj = getMap(val);
		if (jobj == null) {
			return new ObjectTypesSupported();
		}
		List<String> labels = getBitStringLabels(ObjectTypesSupported.class);
		ObjectTypesSupported ots = new ObjectTypesSupported();
		if (labels == null) {
			return ots;
		}
		for (String label: labels) {
			ots.set(ObjectType.forName(label), jobj.get(label, false));
		}
		return ots;
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// OctetString Subtypes 
	/////////////////////////////////////////////////////////////////////////////////////////
	
	public static JsonObject parseWeekNDay(WeekNDay enc) {
		JsonObject jo = new JsonObject();
		if (enc.getMonth() != Month.UNSPECIFIED) {
			jo.put("Month", enc.getMonth().toString());
		}
		if (enc.getWeekOfMonth() != WeekOfMonth.any) {
			jo.put("Week of Month", enc.getWeekOfMonth().intValue());
		}
		if (enc.getDayOfWeek() != DayOfWeek.UNSPECIFIED) {
			jo.put("Day of Week", enc.getDayOfWeek().toString());
		}
		return jo;
	}
	
	public static WeekNDay formatWeekNDay(Value val) {
		JsonObject jobj = getMap(val);
		if (jobj == null) {
			return new WeekNDay(Month.UNSPECIFIED, WeekOfMonth.any, DayOfWeek.UNSPECIFIED);
		}
		Month mn = Month.valueOf(jobj.get("Month", Month.UNSPECIFIED.toString()));
		WeekOfMonth wk = WeekOfMonth.valueOf(jobj.get("Week of Month", WeekOfMonth.any.byteValue()));
		DayOfWeek dw = DayOfWeek.valueOf(jobj.get("Day of Week", DayOfWeek.UNSPECIFIED.toString()));
		return new WeekNDay(mn, wk, dw);
	}
	
	public static JsonArray getArray(Value val) {
	    JsonArray arr = val.getArray();
	    if (arr != null) {
	        return arr;
	    }
	    String s = val.getString();
	    if (s != null && s.startsWith("[")) {
	        try {
	            arr = new JsonArray(s);
	            return arr;
	        } catch (Exception e) {
	            return null;
	        }
	    }
	    return null;
	}
	
	public static JsonObject getMap(Value val) {
        JsonObject obj = val.getMap();
        if (obj != null) {
            return obj;
        }
        String s = val.getString();
        if (s != null && s.startsWith("{")) {
            try {
                obj = new JsonObject(s);
                return obj;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
	
}
