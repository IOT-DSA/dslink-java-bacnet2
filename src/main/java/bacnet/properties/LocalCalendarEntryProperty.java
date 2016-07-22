package bacnet.properties;

import java.util.Calendar;
import java.util.GregorianCalendar;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.CalendarEntry;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Date;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;


public class LocalCalendarEntryProperty extends LocalBacnetProperty{
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	CalendarEntry entry;

	public LocalCalendarEntryProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalCalendarEntryProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		Date now = new Date();
		Encodable enc = new CalendarEntry(now);
		bacnetObj.writeProperty(PropertyIdentifier.dateList, enc);
		node.setValueType(ValueType.TIME);
		node.setValue(new Value(now.getDay()));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}
	
	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			long millis = newVal.getTime();
			GregorianCalendar gc = (GregorianCalendar) Calendar.getInstance();
			gc.setTimeInMillis(millis);
			CalendarEntry entry = new CalendarEntry( new Date(gc)); 		
			bacnetObj.writeProperty(propertyId, entry);
		    node.setAttribute(propertyId.toString(), newVal);
		}
	}
	
	public void updatePropertyValue(Encodable enc) {
		
	}
}

