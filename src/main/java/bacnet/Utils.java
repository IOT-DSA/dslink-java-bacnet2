package bacnet;

import java.util.Arrays;
import java.util.List;

import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.StringUtils;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;

import com.serotonin.bacnet4j.*;
import com.serotonin.bacnet4j.base.BACnetUtils;
import com.serotonin.bacnet4j.enums.DayOfWeek;
import com.serotonin.bacnet4j.enums.Month;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.CalendarEntry;
import com.serotonin.bacnet4j.type.constructed.DailySchedule;
import com.serotonin.bacnet4j.type.constructed.DateRange;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.DaysOfWeek;
import com.serotonin.bacnet4j.type.constructed.Destination;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.Recipient;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.SpecialEvent;
import com.serotonin.bacnet4j.type.constructed.TimeStamp;
import com.serotonin.bacnet4j.type.constructed.TimeValue;
import com.serotonin.bacnet4j.type.constructed.WeekNDay;
import com.serotonin.bacnet4j.type.constructed.WeekNDay.WeekOfMonth;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.EngineeringUnits;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.LifeSafetyState;
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
import com.serotonin.bacnet4j.util.PropertyReferences;

/**
 * @author Samuel Grenier
 */
public class Utils {

	public static Address toAddress(int netNum, String mac) {
		mac = mac.trim();
		int colon = mac.indexOf(":");
		if (colon == -1) {
			OctetString os = new OctetString(BACnetUtils.dottedStringToBytes(mac));
			return new Address(netNum, os);
		} else {
			byte[] ip = BACnetUtils.dottedStringToBytes(mac.substring(0, colon));
			int port = Integer.parseInt(mac.substring(colon + 1));
			return IpNetworkUtils.toAddress(netNum, ip, port);
		}
	}

	/**
	 * Returns the IP or MSTP mac for the given device.
	 */
	public static String getMac(RemoteDevice remoteDevice) {
		try {
			return IpNetworkUtils.toIpPortString(remoteDevice.getAddress().getMacAddress());
		} catch (IllegalArgumentException ignore) {
		}
		return Byte.toString(remoteDevice.getAddress().getMacAddress().getBytes()[0]);
	}

	public static String datetimeToString(DateTime dt) {
		String dat = dateToString(dt.getDate());
		String tim = unparseTime(dt.getTime());
		return dat + "T" + tim;
	}

	public static DateTime datetimeFromString(String str) {
		String[] a = str.split("T");
		Date d = dateFromString(a[0]);
		Time t;
		if (a.length >= 2) {
			t = parseTime(a[1]);
		} else {
			t = Time.UNSPECIFIED;
		}
		return new DateTime(d, t);
	}

	public static String dateToString(Date date) {
		String str = (date.getYear() != Date.UNSPECIFIED_YEAR) ? Integer.toString(date.getCenturyYear()) : "????";
		Month mon = date.getMonth();
		if (mon != Month.UNSPECIFIED) {
			int id = mon.getId();
			str += (id < 10) ? "-0" + Integer.toString(id) : "-" + Integer.toString(id);
		} else {
			str += "-??";
		}
		int day = date.getDay();
		if (day != Date.UNSPECIFIED_DAY) {
			str += (day < 10) ? "-0" + Integer.toString(day) : "-" + Integer.toString(day);
		} else {
			str += "-??";
		}
		return str;
	}

	public static Date dateFromString(String str) {
		int yr;
		Month mon;
		int day;
		try {
			yr = Integer.parseInt(str.substring(0, 4));
		} catch (NumberFormatException e) {
			yr = Date.UNSPECIFIED_YEAR;
		}
		try {
			mon = Month.valueOf(Integer.parseInt(str.substring(5, 7)));
		} catch (NumberFormatException e) {
			mon = Month.UNSPECIFIED;
		}
		try {
			day = Integer.parseInt(str.substring(8, 10));
		} catch (NumberFormatException e) {
			day = Date.UNSPECIFIED_DAY;
		}
		return new Date(yr, mon, day, null);

	}

	public static String unparseTime(Time time) {
		String str = "";
		String part = (time.isHourUnspecified()) ? "???" : ("00" + Integer.toString(time.getHour()));
		str += part.substring(part.length() - 2) + ":";
		part = (time.isMinuteUnspecified()) ? "???" : ("00" + Integer.toString(time.getMinute()));
		str += part.substring(part.length() - 2) + ":";
		part = (time.isSecondUnspecified()) ? "???" : ("00" + Integer.toString(time.getSecond()));
		str += part.substring(part.length() - 2) + ".";
		part = (time.isHundredthUnspecified()) ? "????" : (Integer.toString(time.getHundredth()) + "000");
		str += part.substring(0, 3);
		return str;

	}

	public static Time parseTime(String str) {
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

	public static String timestampToString(TimeStamp ts) {
		if (ts.isDateTime()) {
			return datetimeToString(ts.getDateTime());
		}
		if (ts.isSequenceNumber()) {
			return ts.getSequenceNumber().toString();
		}
		if (ts.isTime()) {
			return unparseTime(ts.getTime());
		}
		return null;
	}

	public static TimeStamp timestampFromString(String str) {
		try {
			UnsignedInteger num = new UnsignedInteger(Integer.parseInt(str));
			return new TimeStamp(num);
		} catch (NumberFormatException e) {
		}
		if (str.length() > 12) {
			DateTime dt = datetimeFromString(str);
			return new TimeStamp(dt);
		}
		Time t = parseTime(str);
		return new TimeStamp(t);
	}

	public static EventState eventStateFromString(String str) {
		if (str.equals("normal"))
			return new EventState(0);
		if (str.equals("fault"))
			return new EventState(1);
		if (str.equals("offnormal"))
			return new EventState(2);
		if (str.equals("highLimit"))
			return new EventState(3);
		if (str.equals("lowLimit"))
			return new EventState(4);
		if (str.equals("lifeSafetyAlarm"))
			return new EventState(5);
		return new EventState(0);
	}

	public static Object interpretPrimitive(Primitive p) {
		if (p instanceof com.serotonin.bacnet4j.type.primitive.Boolean)
			return ((com.serotonin.bacnet4j.type.primitive.Boolean) p).booleanValue();
		else if (p instanceof Real)
			return ((Real) p).floatValue();
		else if (p instanceof SignedInteger)
			return ((SignedInteger) p).intValue();
		else if (p instanceof com.serotonin.bacnet4j.type.primitive.Double)
			return ((com.serotonin.bacnet4j.type.primitive.Double) p).doubleValue();
		else if (p instanceof Enumerated)
			return ((Enumerated) p).intValue();
		else if (p instanceof UnsignedInteger)
			return ((UnsignedInteger) p).intValue();
		else if (p instanceof Null)
			return null;
		else if (p instanceof Date)
			return dateToString((Date) p);
		else if (p instanceof Time)
			return unparseTime((Time) p);
		else
			return p.toString();
	}

	public static Primitive parsePrimitive(Object o, byte typeid) {
		if (o == null)
			return new Null();
		if (o instanceof Boolean)
			return new com.serotonin.bacnet4j.type.primitive.Boolean(((Boolean) o).booleanValue());
		if (o instanceof Number) {
			if (typeid == SignedInteger.TYPE_ID)
				return new SignedInteger(((Number) o).intValue());
			if (typeid == UnsignedInteger.TYPE_ID)
				return new UnsignedInteger(((Number) o).intValue());
			if (typeid == Enumerated.TYPE_ID)
				return new Enumerated(((Number) o).intValue());
			if (typeid == Real.TYPE_ID)
				return new Real(((Number) o).floatValue());
			return new com.serotonin.bacnet4j.type.primitive.Double(((Number) o).doubleValue());
		}
		if (o instanceof String) {
			if (typeid == Date.TYPE_ID)
				return dateFromString((String) o);
			if (typeid == Time.TYPE_ID)
				return parseTime((String) o);
			if (typeid == ObjectIdentifier.TYPE_ID) {
				ObjectIdentifier oid = oidFromString((String) o);
				if (oid != null)
					return oid;
				else
					return new CharacterString((String) o);
			}
			if (typeid == OctetString.TYPE_ID)
				return octetStrFromString((String) o);
			if (typeid == BitString.TYPE_ID)
				return bitStrFromString((String) o);
			return new CharacterString((String) o);
		}
		return null;
	}

	public static byte getPrimitiveType(Primitive p) {
		if (p instanceof com.serotonin.bacnet4j.type.primitive.Boolean)
			return com.serotonin.bacnet4j.type.primitive.Boolean.TYPE_ID;
		else if (p instanceof Real)
			return Real.TYPE_ID;
		else if (p instanceof SignedInteger)
			return SignedInteger.TYPE_ID;
		else if (p instanceof com.serotonin.bacnet4j.type.primitive.Double)
			return com.serotonin.bacnet4j.type.primitive.Double.TYPE_ID;
		else if (p instanceof Enumerated)
			return Enumerated.TYPE_ID;
		else if (p instanceof UnsignedInteger)
			return UnsignedInteger.TYPE_ID;
		else if (p instanceof Null)
			return Null.TYPE_ID;
		else if (p instanceof Date)
			return Date.TYPE_ID;
		else if (p instanceof Time)
			return Time.TYPE_ID;
		else if (p instanceof ObjectIdentifier)
			return ObjectIdentifier.TYPE_ID;
		else if (p instanceof OctetString)
			return OctetString.TYPE_ID;
		else if (p instanceof BitString)
			return BitString.TYPE_ID;
		else
			return CharacterString.TYPE_ID;
	}

	public static ObjectIdentifier oidFromString(String str) {
		String[] a = str.split(" ");
		String numstr = a[a.length - 1];
		int num;
		try {
			num = Integer.parseInt(numstr);
		} catch (NumberFormatException e) {
			return null;
		}
		ObjectType ot = DeviceFolder.parseObjectType(str.substring(0, str.length() - numstr.length() - 1));
		if (ot == null)
			return null;
		return new ObjectIdentifier(ot, num);
	}

	public static OctetString octetStrFromString(String str) {
		str = str.trim().substring(1, str.length() - 1);
		String[] strings = str.split(",");
		byte[] bytes = new byte[strings.length];
		for (int i = 0; i < strings.length; i++) {
			String s = strings[i];
			bytes[i] = Byte.parseByte(s.trim());
		}
		return new OctetString(bytes);
	}

	public static BitString bitStrFromString(String str) {
		str = str.trim().substring(1, str.length() - 1);
		String[] strings = str.split(",");
		boolean[] bools = new boolean[strings.length];
		for (int i = 0; i < strings.length; i++) {
			String s = strings[i];
			bools[i] = Boolean.parseBoolean(s.trim());
		}
		return new BitString(bools);
	}

	public static JsonObject dateToJson(Date date) {
		JsonObject dateObj = new JsonObject();
		if (date.getYear() != Date.UNSPECIFIED_YEAR)
			dateObj.put("Year", date.getCenturyYear());
		if (date.getMonth() != Month.UNSPECIFIED)
			dateObj.put("Month", date.getMonth().toString());
		if (date.getDay() != Date.UNSPECIFIED_DAY)
			dateObj.put("Day", date.getDay());
		if (date.getDayOfWeek() != DayOfWeek.UNSPECIFIED)
			dateObj.put("Day of Week", date.getDayOfWeek().toString());
		return dateObj;
	}

	public static Date dateFromJson(JsonObject jobj) {
		Object y = jobj.get("Year");
		Object m = jobj.get("Month");
		Object d = jobj.get("Day");
		Object w = jobj.get("Day of Week");
		int yr;
		Month mon;
		int day;
		DayOfWeek dow;
		yr = (y instanceof Number) ? ((Number) y).intValue() : Date.UNSPECIFIED_YEAR;
		mon = (m instanceof String) ? Month.valueOf(((String) m)) : Month.UNSPECIFIED;
		day = (d instanceof Number) ? ((Number) d).intValue() : Date.UNSPECIFIED_DAY;
		dow = (w instanceof String) ? DayOfWeek.valueOf((String) w) : DayOfWeek.UNSPECIFIED;
		return new Date(yr, mon, day, dow);
	}

	public static JsonObject dateRangeToJson(DateRange dr) {
		JsonObject jo = new JsonObject();
		jo.put("Start Date", dateToJson(dr.getStartDate()));
		jo.put("End Date", dateToJson(dr.getEndDate()));
		return jo;
	}

	public static DateRange dateRangeFromJson(JsonObject jobj) {
		Object sobj = jobj.get("Start Date");
		Object eobj = jobj.get("End Date");
		if (sobj instanceof JsonObject && eobj instanceof JsonObject) {
			Date start = dateFromJson((JsonObject) sobj);
			Date end = dateFromJson((JsonObject) eobj);
			return new DateRange(start, end);
		}
		return null;
	}

	public static JsonObject weekNDayToJson(WeekNDay wd) {
		JsonObject jo = new JsonObject();
		if (wd.getMonth() != Month.UNSPECIFIED)
			jo.put("Month", wd.getMonth().toString());
		if (wd.getWeekOfMonth() != WeekOfMonth.any)
			jo.put("Week of Month", wd.getWeekOfMonth().intValue());
		if (wd.getDayOfWeek() != DayOfWeek.UNSPECIFIED)
			jo.put("Day of Week", wd.getDayOfWeek().toString());
		return jo;
	}

	public static WeekNDay weekNDayFromJson(JsonObject jobj) {
		Object m = jobj.get("Month");
		Object w = jobj.get("Week of Month");
		Object d = jobj.get("Day of Week");
		Month mon;
		WeekOfMonth week;
		DayOfWeek day;
		mon = (m instanceof String) ? Month.valueOf(((String) m)) : Month.UNSPECIFIED;
		week = (w instanceof Number) ? WeekOfMonth.valueOf(((Number) w).byteValue()) : WeekOfMonth.any;
		day = (d instanceof String) ? DayOfWeek.valueOf((String) w) : DayOfWeek.UNSPECIFIED;
		return new WeekNDay(mon, week, day);
	}

	public static JsonObject calendarEntryToJson(CalendarEntry ce) {
		JsonObject jo = new JsonObject();
		if (ce.isDate()) {
			jo.put("Date", dateToJson(ce.getDate()));
		} else if (ce.isDateRange()) {
			jo.put("Date Range", dateRangeToJson(ce.getDateRange()));
		} else if (ce.isWeekNDay()) {
			jo.put("Week and Day", weekNDayToJson(ce.getWeekNDay()));
		}
		return jo;
	}

	public static CalendarEntry calendarEntryFromJson(JsonObject jobj) {
		Object dateobj = jobj.get("Date");
		if (dateobj instanceof JsonObject) {
			Date date = dateFromJson((JsonObject) dateobj);
			return new CalendarEntry(date);
		}
		Object rangeobj = jobj.get("Date Range");
		if (rangeobj instanceof JsonObject) {
			DateRange range = dateRangeFromJson((JsonObject) rangeobj);
			if (range != null)
				return new CalendarEntry(range);
		}
		Object wdobj = jobj.get("Week and Day");
		if (wdobj instanceof JsonObject) {
			WeekNDay wd = weekNDayFromJson((JsonObject) wdobj);
			return new CalendarEntry(wd);
		}
		return null;
	}

	public static JsonObject timeValueToJson(TimeValue tv) {
		JsonObject jo = new JsonObject();
		jo.put("Time", unparseTime(tv.getTime()));
		jo.put("Value", interpretPrimitive(tv.getValue()));
		return jo;
	}

	private static TimeValue timeValueFromJson(JsonObject jobj, byte typeid) {
		Object tim = jobj.get("Time");
		if (tim instanceof String) {
			Time time = parseTime((String) tim);
			Primitive val = parsePrimitive(jobj.get("Value"), typeid);
			return new TimeValue(time, val);
		}
		return null;
	}

	public static JsonObject specialEventToJson(SpecialEvent se) {
		JsonObject jo = new JsonObject();
		if (se.isCalendarReference()) {
			jo.put("Calendar Reference", se.getCalendarReference().toString());
		} else {
			jo.put("Calendar Entry", calendarEntryToJson(se.getCalendarEntry()));
		}
		JsonArray jarr = new JsonArray();
		for (TimeValue tv : se.getListOfTimeValues()) {
			jarr.add(timeValueToJson(tv));
		}
		jo.put("TimeValue List", jarr);
		jo.put("Event Priority", se.getEventPriority().intValue());
		return jo;
	}

	public static SpecialEvent specialEventFromJson(JsonObject jobj, byte typeid) {
		Object pobj = jobj.get("Event Priority");
		if (!(pobj instanceof Number))
			return null;
		UnsignedInteger epriority = new UnsignedInteger(((Number) pobj).intValue());
		Object tvlobj = jobj.get("TimeValue List");
		if (!(tvlobj instanceof JsonArray))
			return null;
		SequenceOf<TimeValue> tvseq = new SequenceOf<TimeValue>();
		for (Object o : (JsonArray) tvlobj) {
			if (o instanceof JsonObject) {
				TimeValue tv = timeValueFromJson((JsonObject) o, typeid);
				if (tv != null)
					tvseq.add(tv);
			}
		}
		Object refobj = jobj.get("Calendar Reference");
		if (refobj instanceof String) {
			ObjectIdentifier ref = oidFromString((String) refobj);
			if (ref != null)
				return new SpecialEvent(ref, tvseq, epriority);
		}
		Object calobj = jobj.get("Calendar Entry");
		if (!(calobj instanceof JsonObject))
			return null;
		CalendarEntry entry = calendarEntryFromJson((JsonObject) calobj);
		if (entry != null)
			return new SpecialEvent(entry, tvseq, epriority);
		return null;
	}

	public static JsonObject destinationToJson(Destination dest) {
		JsonObject jo = new JsonObject();
		jo.put("Valid Days", daysOfWeekToJson(dest.getValidDays()));
		jo.put("From Time", unparseTime(dest.getFromTime()));
		jo.put("To Time", unparseTime(dest.getToTime()));
		jo.put("Recipient", recipientToJson(dest.getRecipient()));
		jo.put("Process Identifier", dest.getProcessIdentifier().bigIntegerValue());
		jo.put("Issue Confirmed Notifications", dest.getIssueConfirmedNotifications().booleanValue());
		jo.put("Transitions", eventTransitionBitsToJson(dest.getTransitions()));
		return jo;
	}

	public static Destination destinationFromJson(JsonObject jo) {
		Object vdobj = jo.get("Valid Days");
		Object ftimeobj = jo.get("From Time");
		Object ttimeobj = jo.get("To Time");
		Object recipobj = jo.get("Recipient");
		Object procidobj = jo.get("Process Identifier");
		Object confirmedobj = jo.get("Issue Confirmed Notifications");
		Object transobj = jo.get("Transitions");
		if (!(recipobj instanceof JsonObject) || !(procidobj instanceof Number) || !(confirmedobj instanceof Boolean)
				|| !(transobj instanceof JsonObject)) {
			return null;
		}

		Recipient recip = recipientFromJson((JsonObject) recipobj);
		if (recip == null)
			return null;
		UnsignedInteger procid = new UnsignedInteger(((Number) procidobj).intValue());
		com.serotonin.bacnet4j.type.primitive.Boolean confirmedNotif = new com.serotonin.bacnet4j.type.primitive.Boolean(
				((Boolean) confirmedobj).booleanValue());
		EventTransitionBits transitions = eventTransitionBitsFromJson((JsonObject) transobj);
		if (vdobj instanceof JsonObject && ftimeobj instanceof String && ttimeobj instanceof String) {
			DaysOfWeek validDays = daysOfWeekFromJson((JsonObject) vdobj);
			Time fromTime = parseTime((String) ftimeobj);
			Time toTime = parseTime((String) ttimeobj);
			return new Destination(validDays, fromTime, toTime, recip, procid, confirmedNotif, transitions);
		}
		return new Destination(recip, procid, confirmedNotif, transitions);
	}

	private static JsonObject recipientToJson(Recipient recipient) {
		JsonObject jo = new JsonObject();
		if (recipient.isDevice()) {
			jo.put("Object Identifier", recipient.getDevice().toString());
		}
		if (recipient.isAddress()) {
			Address addr = recipient.getAddress();
			jo.put("Network Number", addr.getNetworkNumber().bigIntegerValue());
			jo.put("MAC Address", BACnetUtils.bytesToDottedString(addr.getMacAddress().getBytes()));
		}
		return jo;
	}

	public static Recipient recipientFromJson(JsonObject jo) {
		Object oidobj = jo.get("Object Identifier");
		Object nnobj = jo.get("Network Number");
		Object macobj = jo.get("MAC Address");
		if (oidobj instanceof String) {
			ObjectIdentifier oid = oidFromString((String) oidobj);
			if (oid != null)
				return new Recipient(oid);
		}
		if (nnobj instanceof Number && macobj instanceof String) {
			Address addr = toAddress(((Number) nnobj).intValue(), ((String) macobj));
			return new Recipient(addr);
		}
		return null;
	}

	private static JsonObject eventTransitionBitsToJson(EventTransitionBits transitions) {
		JsonObject jo = new JsonObject();
		jo.put("To Offnormal", transitions.isToOffnormal());
		jo.put("To Fault", transitions.isToFault());
		jo.put("To Normal", transitions.isToNormal());
		return jo;
	}

	private static EventTransitionBits eventTransitionBitsFromJson(JsonObject jo) {
		Object offnormobj = jo.get("To Offnormal");
		Object faultobj = jo.get("To Fault");
		Object normobj = jo.get("To Normal");
		boolean offnorm = (offnormobj instanceof Boolean) ? ((Boolean) offnormobj).booleanValue() : false;
		boolean fault = (faultobj instanceof Boolean) ? ((Boolean) faultobj).booleanValue() : false;
		boolean norm = (normobj instanceof Boolean) ? ((Boolean) normobj).booleanValue() : false;
		return new EventTransitionBits(offnorm, fault, norm);
	}

	private static JsonObject daysOfWeekToJson(DaysOfWeek days) {
		JsonObject jo = new JsonObject();
		jo.put("Monday", days.isMonday());
		jo.put("Tuesday", days.isTuesday());
		jo.put("Wednesday", days.isWednesday());
		jo.put("Thursday", days.isThursday());
		jo.put("Friday", days.isFriday());
		jo.put("Saturday", days.isSaturday());
		jo.put("Sunday", days.isSunday());
		return jo;
	}

	private static DaysOfWeek daysOfWeekFromJson(JsonObject jo) {
		Object monobj = jo.get("Monday");
		Object tuesobj = jo.get("Tuesday");
		Object wedsobj = jo.get("Wednesday");
		Object thursobj = jo.get("Thursday");
		Object friobj = jo.get("Friday");
		Object satobj = jo.get("Saturday");
		Object sunobj = jo.get("Sunday");
		boolean mon = (monobj instanceof Boolean) ? ((Boolean) monobj).booleanValue() : false;
		boolean tues = (tuesobj instanceof Boolean) ? ((Boolean) tuesobj).booleanValue() : false;
		boolean weds = (wedsobj instanceof Boolean) ? ((Boolean) wedsobj).booleanValue() : false;
		boolean thurs = (thursobj instanceof Boolean) ? ((Boolean) thursobj).booleanValue() : false;
		boolean fri = (friobj instanceof Boolean) ? ((Boolean) friobj).booleanValue() : false;
		boolean sat = (satobj instanceof Boolean) ? ((Boolean) satobj).booleanValue() : false;
		boolean sun = (sunobj instanceof Boolean) ? ((Boolean) sunobj).booleanValue() : false;
		DaysOfWeek dow = new DaysOfWeek();
		dow.setMonday(mon);
		dow.setTuesday(tues);
		dow.setWednesday(weds);
		dow.setThursday(thurs);
		dow.setFriday(fri);
		dow.setSaturday(sat);
		dow.setSunday(sun);
		return dow;
	}

	public static Encodable encodeJsonArray(JsonArray jarr, PropertyIdentifier prop, byte typeid) {
		if (prop.equals(PropertyIdentifier.weeklySchedule)) {
			return jsonArrayToWeeklySchedule(jarr, typeid);
		} else if (prop.equals(PropertyIdentifier.exceptionSchedule)) {
			return jsonArrayToExceptionSchedule(jarr, typeid);
		} else if (prop.equals(PropertyIdentifier.dateList)) {
			return jsonArrayToDateList(jarr);
		} else if (prop.equals(PropertyIdentifier.recipientList)) {
			return jsonArrayToRecipientList(jarr);
		} else if (prop.equals(PropertyIdentifier.priority)) {
			return jsonArrayToPriority(jarr);
		} else if (prop.equals(PropertyIdentifier.ackRequired)) {
			return jsonArrayToAckRequired(jarr);
		}
		return null;
	}

	public static EventTransitionBits jsonArrayToAckRequired(JsonArray jarr) {
		Object obj0 = jarr.get(0);
		Object obj1 = jarr.get(1);
		Object obj2 = jarr.get(2);
		if (obj0 instanceof String) {
			obj0 = Boolean.parseBoolean((String) obj0);
		}
		if (!(obj0 instanceof Boolean))
			return null;

		if (obj1 instanceof String) {
			obj1 = Boolean.parseBoolean((String) obj1);
		}
		if (!(obj1 instanceof Boolean))
			return null;

		if (obj2 instanceof String) {
			obj2 = Boolean.parseBoolean((String) obj2);
		}
		if (!(obj2 instanceof Boolean))
			return null;

		return new EventTransitionBits((Boolean) obj0, (Boolean) obj1, (Boolean) obj2);

	}

	public static SequenceOf<UnsignedInteger> jsonArrayToPriority(JsonArray jarr) {
		SequenceOf<UnsignedInteger> seq = new SequenceOf<UnsignedInteger>();
		for (Object obj : jarr) {
			if (obj instanceof String) {
				try {
					obj = Integer.parseInt((String) obj);
				} catch (NumberFormatException e) {
				}
			}
			if (obj instanceof Number) {
				seq.add(new UnsignedInteger(((Number) obj).intValue()));
			}
		}
		return seq;
	}

	public static SequenceOf<Destination> jsonArrayToRecipientList(JsonArray jarr) {
		SequenceOf<Destination> seq = new SequenceOf<Destination>();
		for (Object obj : jarr) {
			if (obj instanceof String) {
				obj = new JsonObject((String) obj);
			}
			if (obj instanceof JsonObject) {
				Destination dest = destinationFromJson((JsonObject) obj);
				if (dest != null)
					seq.add(dest);
			}
		}
		return seq;
	}

	public static SequenceOf<DailySchedule> jsonArrayToWeeklySchedule(JsonArray jarr, byte typeid) {
		SequenceOf<DailySchedule> seq = new SequenceOf<DailySchedule>();
		for (Object obj : jarr) {
			if (obj instanceof String) {
				obj = new JsonArray((String) obj);
			}
			if (obj instanceof JsonArray) {
				SequenceOf<TimeValue> tvseq = new SequenceOf<TimeValue>();
				for (Object tvobj : (JsonArray) obj) {
					if (tvobj instanceof JsonObject) {
						TimeValue tv = timeValueFromJson((JsonObject) tvobj, typeid);
						if (tv != null)
							tvseq.add(tv);
					}
				}
				seq.add(new DailySchedule(tvseq));
			}
		}
		return seq;
	}

	public static SequenceOf<SpecialEvent> jsonArrayToExceptionSchedule(JsonArray jarr, byte typeid) {
		SequenceOf<SpecialEvent> seq = new SequenceOf<SpecialEvent>();
		for (Object obj : jarr) {
			if (obj instanceof String) {
				obj = new JsonObject((String) obj);
			}
			if (obj instanceof JsonObject) {
				SpecialEvent spec = specialEventFromJson((JsonObject) obj, typeid);
				if (spec != null)
					seq.add(spec);
			}
		}
		return seq;
	}

	public static SequenceOf<CalendarEntry> jsonArrayToDateList(JsonArray jarr) {
		SequenceOf<CalendarEntry> seq = new SequenceOf<CalendarEntry>();
		for (Object obj : jarr) {
			if (obj instanceof String) {
				obj = new JsonObject((String) obj);
			}
			if (obj instanceof JsonObject) {
				CalendarEntry entry = calendarEntryFromJson((JsonObject) obj);
				if (entry != null)
					seq.add(entry);
			}
		}
		return seq;
	}

	public static <E> String[] enumNames(Class<E> enumData) {
		String valuesStr = Arrays.toString(enumData.getEnumConstants());
		return valuesStr.substring(1, valuesStr.length() - 1).replace(" ", "").split(",");
	}

	public static String[] enumeratedObjectTypeNames() {
		String valuesStr = Arrays.toString(ObjectType.ALL);
		return valuesStr.substring(1, valuesStr.length() - 1).replace(" ", "").split(",");
	}

	public static ObjectType parseObjectType(String typeString) {

		for (ObjectType type : ObjectType.ALL) {
			if (type.toString().replace(" ", "").equals(typeString.replace(" ", ""))) {
				return type;
			}
		}

		return null;

	}

	public static DataType getDataType(ObjectType objectType) {
		if (isOneOf(objectType, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
			return DataType.BINARY;
		} else if (isOneOf(objectType, ObjectType.multiStateInput, ObjectType.multiStateOutput,
				ObjectType.multiStateValue, ObjectType.lifeSafetyPoint, ObjectType.lifeSafetyZone)) {
			return DataType.MULTISTATE;
		} else if (isOneOf(objectType, ObjectType.calendar)) {
			return DataType.BOOLEAN;
		} else {
			return DataType.NUMERIC;
		}
	}

	public static void addPropertyReferences(PropertyReferences refs, ObjectIdentifier oid) {
		refs.add(oid, PropertyIdentifier.objectName);

		ObjectType type = oid.getObjectType();
		if (isOneOf(type, ObjectType.accumulator)) {
			refs.add(oid, PropertyIdentifier.units);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (isOneOf(type, ObjectType.analogInput, ObjectType.analogOutput, ObjectType.analogValue,
				ObjectType.pulseConverter)) {
			refs.add(oid, PropertyIdentifier.units);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (isOneOf(type, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
			refs.add(oid, PropertyIdentifier.inactiveText);
			refs.add(oid, PropertyIdentifier.activeText);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (isOneOf(type, ObjectType.device)) {
			refs.add(oid, PropertyIdentifier.modelName);
		} else if (isOneOf(type, ObjectType.lifeSafetyPoint)) {
			refs.add(oid, PropertyIdentifier.units);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (isOneOf(type, ObjectType.loop)) {
			refs.add(oid, PropertyIdentifier.outputUnits);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (isOneOf(type, ObjectType.multiStateInput, ObjectType.multiStateOutput, ObjectType.multiStateValue)) {
			refs.add(oid, PropertyIdentifier.stateText);
			refs.add(oid, PropertyIdentifier.presentValue);
		} else if (isOneOf(type, ObjectType.schedule)) {
			refs.add(oid, PropertyIdentifier.presentValue);
			refs.add(oid, PropertyIdentifier.effectivePeriod);
			refs.add(oid, PropertyIdentifier.weeklySchedule);
			refs.add(oid, PropertyIdentifier.exceptionSchedule);
		} else if (isOneOf(type, ObjectType.trendLog)) {
			refs.add(oid, PropertyIdentifier.logDeviceObjectProperty);
			refs.add(oid, PropertyIdentifier.recordCount);
			refs.add(oid, PropertyIdentifier.startTime);
			refs.add(oid, PropertyIdentifier.stopTime);
			refs.add(oid, PropertyIdentifier.bufferSize);
			// refs.add(oid, PropertyIdentifier.logBuffer);
		} else if (isOneOf(type, ObjectType.notificationClass)) {
			refs.add(oid, PropertyIdentifier.notificationClass);
			refs.add(oid, PropertyIdentifier.priority);
			refs.add(oid, PropertyIdentifier.ackRequired);
			refs.add(oid, PropertyIdentifier.recipientList);
		} else if (isOneOf(type, ObjectType.calendar)) {
			refs.add(oid, PropertyIdentifier.presentValue);
			refs.add(oid, PropertyIdentifier.dateList);
		}
	}

	public static boolean isOneOf(int objectTypeId, ObjectType... types) {
		for (ObjectType type : types) {
			if (type.intValue() == objectTypeId)
				return true;
		}
		return false;
	}

	public static boolean isOneOf(ObjectType objectType, ObjectType... types) {
		return isOneOf(objectType.intValue(), types);
	}

	public static String toLegalName(String s) {
		if (s == null)
			return "";
		return StringUtils.encodeName(s);
	}

	public static Encodable valueToEncodable(DataType dataType, Value value, ObjectType objectType,
			PropertyIdentifier propertyId, List<String> unitsDescription) {
		Class<? extends Encodable> clazz = ObjectProperties.getPropertyTypeDefinition(objectType, propertyId)
				.getClazz();

		switch (dataType) {
		case BINARY: {
			boolean b;
			if (value.getType().compare(ValueType.BOOL))
				b = value.getBool();
			else
				b = (Boolean.parseBoolean(value.getString()) || value.getString().equals("1"));
			if (clazz == BinaryPV.class) {
				if (b)
					return BinaryPV.active;
				return BinaryPV.inactive;
			}

			if (clazz == UnsignedInteger.class)
				return new UnsignedInteger(b ? 1 : 0);

			if (clazz == LifeSafetyState.class)
				return new LifeSafetyState(b ? 1 : 0);

			if (clazz == Real.class)
				return new Real(b ? 1 : 0);
		}
		case NUMERIC: {
			double d;
			if (value.getType() == ValueType.NUMBER)
				d = value.getNumber().doubleValue();
			else
				d = Double.parseDouble(value.getString());
			if (clazz == BinaryPV.class) {
				if (d != 0)
					return BinaryPV.active;
				return BinaryPV.inactive;
			}

			if (clazz == UnsignedInteger.class)
				return new UnsignedInteger((int) d);

			if (clazz == LifeSafetyState.class)
				return new LifeSafetyState((int) d);

			if (clazz == Real.class)
				return new Real((float) d);
		}
		case ALPHANUMERIC: {
			if (clazz == BinaryPV.class) {
				boolean b;
				if (value.getType().compare(ValueType.BOOL))
					b = value.getBool();
				else
					b = (Boolean.parseBoolean(value.getString()) || value.getString().equals("1"));
				if (b)
					return BinaryPV.active;
				return BinaryPV.inactive;
			}

			if (clazz == UnsignedInteger.class) {
				int i = Integer.parseInt(value.getString());
				if (value.getType() == ValueType.NUMBER)
					i = value.getNumber().intValue();
				return new UnsignedInteger(i);
			}
			if (clazz == LifeSafetyState.class) {
				int i = Integer.parseInt(value.getString());
				if (value.getType() == ValueType.NUMBER)
					i = value.getNumber().intValue();
				return new LifeSafetyState(i);
			}
			if (clazz == Real.class) {
				float f = (float) Double.parseDouble(value.getString());
				if (value.getType() == ValueType.NUMBER)
					f = value.getNumber().floatValue();
				return new Real(f);
			}
		}
		case MULTISTATE: {
			int i = Integer.parseInt(value.getString());
			if (value.getType().compare(ValueType.ENUM))
				i = unitsDescription.indexOf(value.getString());
			if (clazz == BinaryPV.class) {
				if (i != 0)
					return BinaryPV.active;
				return BinaryPV.inactive;
			}

			if (clazz == UnsignedInteger.class)
				return new UnsignedInteger(i);

			if (clazz == LifeSafetyState.class)
				return new LifeSafetyState(i);

			if (clazz == Real.class)
				return new Real(i);
		}
		default:
			return BinaryPV.inactive;
		}

	}

}
