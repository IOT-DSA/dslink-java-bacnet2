package bacnet;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.enums.DayOfWeek;
import com.serotonin.bacnet4j.enums.Month;
import com.serotonin.bacnet4j.npdu.NPCI.NetworkPriority;
import com.serotonin.bacnet4j.obj.logBuffer.LogBuffer;
import com.serotonin.bacnet4j.type.AmbiguousValue;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.EncodedValue;
import com.serotonin.bacnet4j.type.constructed.BaseType;
import com.serotonin.bacnet4j.type.constructed.DaysOfWeek;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.LimitEnable;
import com.serotonin.bacnet4j.type.constructed.LogStatus;
import com.serotonin.bacnet4j.type.constructed.ObjectTypesSupported;
import com.serotonin.bacnet4j.type.constructed.PriorityValue;
import com.serotonin.bacnet4j.type.constructed.ResultFlags;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.ServicesSupported;
import com.serotonin.bacnet4j.type.constructed.StatusFlags;
import com.serotonin.bacnet4j.type.constructed.WeekNDay;
import com.serotonin.bacnet4j.type.constructed.WeekNDay.WeekOfMonth;
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

public class TypeUtils {
	private static final int MAX_RECURSION_DEPTH = 20; //In case of infinite loops, generally should not be necessary
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TypeUtils.class);

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

	/////////////////////////////////////////////////////////////////////////////////////////
	// Encodable Subtypes
	/////////////////////////////////////////////////////////////////////////////////////////

	public static Pair<ValueType, Value> parseBaseType(BaseType enc, int maxDepth) {
		if (maxDepth == 0) {
			return Pair.of(ValueType.STRING, new Value(enc.toString()));
		}
		maxDepth -= 1;
		if (enc instanceof SequenceOf<?>) {
			return Pair.of(ValueType.ARRAY, new Value(parseSequenceOf((SequenceOf<? extends Encodable>) enc, maxDepth)));
		} else if (enc instanceof PriorityValue) {
			return parseEncodable(((PriorityValue) enc).getValue(), maxDepth);
		} else {
			return Pair.of(ValueType.MAP, new Value(parseNonSequenceConstructed(enc, maxDepth)));
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
			return Pair.of(ValueType.STRING, null); //TODO check that this is correct
		} else if (enc instanceof ObjectIdentifier) {
			return Pair.of(ValueType.STRING, new Value(((ObjectIdentifier) enc).toString()));
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
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// BaseType Subtypes
	/////////////////////////////////////////////////////////////////////////////////////////
	
	public static JsonArray parseSequenceOf(SequenceOf<? extends Encodable> enc, int maxDepth) {
		JsonArray jarr = new JsonArray();
		for (Encodable item: enc) {
			Pair<ValueType, Value> p = parseEncodable(item, maxDepth);
			ValueType vt = p.getLeft();
			Value v = p.getRight();
			if (v == null) {
				jarr.add(null);
			} else if (vt.compare(ValueType.ARRAY)) {
				jarr.add(v.getArray());
			} else if (vt.compare(ValueType.BOOL)) {
				jarr.add(v.getBool());
			} else if (vt.compare(ValueType.MAP)) {
				jarr.add(v.getMap());
			} else if (vt.compare(ValueType.NUMBER)) {
				jarr.add(v.getNumber());
			} else {
				jarr.add(v.getString());
			}
		}
		return jarr;
	}
	
	public static JsonObject parseNonSequenceConstructed(BaseType enc, int maxDepth) {
		Class<? extends BaseType> clazz = enc.getClass();
		Set<Method> gets = new HashSet<Method>();
		Map<String, Method> ises = new HashMap<String, Method>();
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
		
		JsonObject jobj = new JsonObject();
		
		for (Method getMethod: gets) {
			String key = getMethod.getName().substring(3);
			Method isMethod = ises.get(key);
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
						jobj.put(key, v.getArray());
					} else if (vt.compare(ValueType.BOOL)) {
						jobj.put(key, v.getBool());
					} else if (vt.compare(ValueType.MAP)) {
						jobj.put(key, v.getMap());
					} else if (vt.compare(ValueType.NUMBER)) {
						jobj.put(key, v.getNumber());
					} else {
						jobj.put(key, v.getString());
					}
				} else if (o instanceof List) {
					jobj.put(key, listToJsonArray((List<? extends BaseType>) o, maxDepth));
				} else if (o instanceof NetworkPriority) {
					jobj.put(key, ((NetworkPriority) o).toString());
				} else if (o instanceof Number) {
					jobj.put(key, (Number) o);
				} else if (o instanceof String) {
					jobj.put(key, (String) o);
				}
			} catch (Exception e) {
				LOGGER.debug("", e);
			}
		}
		return jobj;
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
		List<String> labels = getBitStringLabels(enc);
		if (labels != null) {
			JsonObject jobj = new JsonObject();
			for (int i = 0; i < labels.size(); i++) {
				String l = labels.get(i);
				boolean b = enc.getArrayValue(i);
				jobj.put(l, Boolean.valueOf(b));
			}
			return Pair.of(ValueType.MAP, new Value(jobj));
		} else {
			JsonArray jarr = new JsonArray();
			for (boolean b : enc.getValue()) {
				jarr.add(Boolean.valueOf(b));
			}
			return Pair.of(ValueType.ARRAY, new Value(jarr));
		}
	}

	public static List<String> getBitStringLabels(BitString enc) {
		if (enc instanceof DaysOfWeek) {
			return Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
		} else if (enc instanceof EventTransitionBits) {
			return Arrays.asList("To Offnormal", "To Fault", "To Normal");
		} else if (enc instanceof LimitEnable) {
			return Arrays.asList("Low Limit Enable", "High Limit Enable");
		} else if (enc instanceof LogStatus) {
			return Arrays.asList("Log Disabled", "Buffer Purged", "Log Interrupted");
		} else if (enc instanceof ObjectTypesSupported) {
			return Utils.getObjectTypeList();
		} else if (enc instanceof ResultFlags) {
			return Arrays.asList("First Item", "Last Item", "More Items");
		} else if (enc instanceof ServicesSupported) {
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
		} else if (enc instanceof StatusFlags) {
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

	public static Pair<ValueType, Value> parseOctetString(OctetString enc) {
		if (enc instanceof WeekNDay) {
			return Pair.of(ValueType.MAP, new Value(parseWeekNDay((WeekNDay) enc)));
		} else {
			return Pair.of(ValueType.STRING, new Value(((OctetString) enc).toString()));
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
	
}
