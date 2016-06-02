package bacnet;

import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;

import com.serotonin.bacnet4j.*;
import com.serotonin.bacnet4j.base.BACnetUtils;
import com.serotonin.bacnet4j.enums.DayOfWeek;
import com.serotonin.bacnet4j.enums.Month;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.CalendarEntry;
import com.serotonin.bacnet4j.type.constructed.DateRange;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.SpecialEvent;
import com.serotonin.bacnet4j.type.constructed.TimeValue;
import com.serotonin.bacnet4j.type.constructed.WeekNDay;
import com.serotonin.bacnet4j.type.constructed.WeekNDay.WeekOfMonth;
import com.serotonin.bacnet4j.type.primitive.Date;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.Primitive;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.Time;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

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
        } catch (IllegalArgumentException ignore) {}
        return Byte.toString(remoteDevice.getAddress().getMacAddress().getBytes()[0]);
    }
    
    
    public static String datetimeToString(DateTime dt) {
    	String dat = dateToString(dt.getDate());
    	String tim = parseTime(dt.getTime());
    	if (tim.equals("Unspecified")) return dat;
    	return dat + "T" + tim;
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
    
    public static String parseTime(Time time) {
    	if (time.isHourUnspecified()) return "Unspecified";
    	String str = "";
    	String part = ("00" + Integer.toString(time.getHour()));
    	str += part.substring(part.length() - 2) + ":";
    	part = (time.isMinuteUnspecified()) ? "000" : ("00" + Integer.toString(time.getMinute()));
    	str += part.substring(part.length() - 2) + ":";
    	part = (time.isSecondUnspecified()) ? "000" : ("00" + Integer.toString(time.getSecond()));
    	str += part.substring(part.length() - 2) + ".";
    	part = (time.isHundredthUnspecified()) ? "0000" : (Integer.toString(time.getHundredth()) + "000");
    	str += part.substring(0, 3);
    	return str;
    	
    }
    
    public static Object interpretPrimitive(Primitive p) {
    	if (p instanceof com.serotonin.bacnet4j.type.primitive.Boolean) return ((com.serotonin.bacnet4j.type.primitive.Boolean) p).booleanValue();
    	else if (p instanceof Real) return ((Real) p).floatValue();
    	else if (p instanceof SignedInteger) return ((SignedInteger) p).intValue();
    	else if (p instanceof com.serotonin.bacnet4j.type.primitive.Double) return ((com.serotonin.bacnet4j.type.primitive.Double) p).doubleValue();
    	else if (p instanceof Enumerated) return ((Enumerated) p).intValue();
    	else if (p instanceof UnsignedInteger) return ((UnsignedInteger) p).intValue();
    	else return p.toString();
    }
    
    public static JsonObject dateToJson(Date date) {
    	JsonObject dateObj = new JsonObject();
    	if (date.getYear() != Date.UNSPECIFIED_YEAR) dateObj.put("Year", date.getCenturyYear());
		if (date.getMonth() != Month.UNSPECIFIED) dateObj.put("Month", date.getMonth().toString());
		if (date.getDay() != Date.UNSPECIFIED_DAY) dateObj.put("Day", date.getDay());
		if (date.getDayOfWeek() != DayOfWeek.UNSPECIFIED) dateObj.put("Day of Week", date.getDayOfWeek().toString());
		return dateObj;
    }
    
    public static JsonObject dateRangeToJson(DateRange dr) {
    	JsonObject jo = new JsonObject();
    	jo.put("Start Date", dateToJson(dr.getStartDate()));
    	jo.put("End Date", dateToJson(dr.getEndDate()));
    	return jo;
    }
    
    public static JsonObject weekNDayToJson(WeekNDay wd) {
    	JsonObject jo = new JsonObject();
    	if (wd.getMonth() != Month.UNSPECIFIED) jo.put("Month", wd.getMonth().toString());
    	if (wd.getWeekOfMonth() != WeekOfMonth.any) jo.put("Week of Month", wd.getWeekOfMonth().intValue());
    	if (wd.getDayOfWeek() != DayOfWeek.UNSPECIFIED) jo.put("Day of Week", wd.getDayOfWeek().toString());
    	return jo;
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
	
	public static JsonObject timeValueToJson(TimeValue tv) {
		JsonObject jo = new JsonObject();
		jo.put("Time", parseTime(tv.getTime()));
		jo.put("Value", interpretPrimitive(tv.getValue()));
		return jo;
	}
	
	public static JsonObject specialEventToString(SpecialEvent se) {
		JsonObject jo = new JsonObject();
		if (se.isCalendarReference()) {
			jo.put("Calendar Reference", se.getCalendarReference().toString());
		} else {
			jo.put("Calendar Entry", calendarEntryToJson(se.getCalendarEntry()));
		}
		JsonArray jarr = new JsonArray();
		for (TimeValue tv: se.getListOfTimeValues()) {
			jarr.add(timeValueToJson(tv));
		}
		jo.put("TimeValue List", jarr);
		jo.put("Event Priority", se.getEventPriority().intValue());
		return jo;
	}
}
