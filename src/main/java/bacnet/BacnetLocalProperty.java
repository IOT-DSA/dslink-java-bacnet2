package bacnet;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.node.value.ValueUtils;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.enums.DayOfWeek;
import com.serotonin.bacnet4j.enums.Month;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.obj.PropertyTypeDefinition;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.OptionalReal;
import com.serotonin.bacnet4j.type.constructed.OptionalUnsigned;
import com.serotonin.bacnet4j.type.constructed.PriorityArray;
import com.serotonin.bacnet4j.type.constructed.PriorityValue;
import com.serotonin.bacnet4j.type.constructed.ValueSource;
import com.serotonin.bacnet4j.type.constructed.WeekNDay;
import com.serotonin.bacnet4j.type.constructed.WeekNDay.WeekOfMonth;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.BitString;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.Date;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.Time;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

public class BacnetLocalProperty {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetLocalProperty.class);
	
	private BacnetLocalObject object;
	private Node node;
	private PropertyIdentifier pid;
	
	BacnetLocalProperty(BacnetLocalObject object, Node node, PropertyIdentifier pid) {
		this.object = object;
		this.node = node;
		this.pid = pid;
		object.properties.put(pid, this);
	}
	
	void init() {
		if (node.getValue() != null) {
			set(node.getValue());
		}
		try {
			object.monitor.checkInReader();
			if (object.obj != null) {
				update(object.obj.readProperty(pid));
			}
			object.monitor.checkOutReader();
		} catch (BACnetServiceException | InterruptedException e) {
			LOGGER.debug("", e);
		}
		
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new Handler<ValuePair>(){
			@Override
			public void handle(ValuePair event) {
				if (event.isFromExternalSource()) {
					set(event.getCurrent());
				}
			}
		});
		
		if (node.getValue() == null) {
			setDefault();
		}
	}
	
	void update(Encodable value) {
		if (value == null) {
			return;
		}
		Pair<ValueType, Value> pair = TypeUtils.parseEncodable(value);
		node.setValueType(pair.getLeft());
		node.setValue(pair.getRight());
	}
	
	private void set(Value value) {
		try {
			object.monitor.checkInReader();
			if (object.obj != null) {
				PropertyTypeDefinition ptd = ObjectProperties.getObjectPropertyTypeDefinition(object.oid.getObjectType(), pid).getPropertyTypeDefinition();
				Class<? extends Encodable> clazz = ptd.getClazz();
				ValueSource valueSource = new ValueSource();
				LOGGER.trace("writing " + pid.toString());
				Encodable enc = TypeUtils.formatEncodable(clazz, value, object.oid.getObjectType(), pid);
				if (enc == null) {
					enc = Null.instance;
				}
				object.obj.writeProperty(valueSource , pid, enc);
					
			}
			object.monitor.checkOutReader();
		} catch (BACnetServiceException | InterruptedException e) {
			LOGGER.debug("", e);
		}
	}
	
	private void setDefault() {
		ValueType vt;
		Value v;
		PropertyTypeDefinition ptd = ObjectProperties.getObjectPropertyTypeDefinition(object.oid.getObjectType(), pid).getPropertyTypeDefinition();
		Class<? extends Encodable> clazz = ptd.getClazz();
		if (PriorityValue.class.isAssignableFrom(clazz)) {
			clazz = ObjectProperties.getObjectPropertyTypeDefinition(object.oid.getObjectType(), PropertyIdentifier.presentValue).getPropertyTypeDefinition().getClazz();
		}
		if (ptd.isList()) {
			vt = ValueType.ARRAY;
			v = new Value(new JsonArray());
		} else if (ptd.isArray()) {
			vt = ValueType.ARRAY;
			JsonArray ja = new JsonArray();
			for (int i=0; i<ptd.getArrayLength(); i++) {
				ja.add(ValueUtils.toObject(getDefault(clazz).getRight()));
			}
			v = new Value(ja);
		} else {
			Pair<ValueType, Value> pair = getDefault(clazz);
			vt = pair.getLeft();
			v = pair.getRight();
		}
		node.setValueType(vt);
		node.setValue(v, true);
	}
	
	@SuppressWarnings("unchecked")
	private Pair<ValueType, Value> getDefault(Class<? extends Encodable> clazz) {
		if (BitString.class.isAssignableFrom(clazz)) {
			List<String> labels = TypeUtils.getBitStringLabels((Class<? extends BitString>) clazz);
			if (labels != null) {
				JsonObject jo = new JsonObject();
				for (String label: labels) {
					jo.put(label, false);
				}
				return Pair.of(ValueType.MAP, new Value(jo));
			} else {
				return Pair.of(ValueType.ARRAY, new Value(new JsonArray()));
			}
		} else if (com.serotonin.bacnet4j.type.primitive.Boolean.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.BOOL, new Value(false));
		} else if (CharacterString.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.STRING, new Value(""));
		} else if (Date.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.MAP, new Value(TypeUtils.parseDate(Date.UNSPECIFIED)));
		} else if (com.serotonin.bacnet4j.type.primitive.Double.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.NUMBER, new Value(0));
		} else if (Enumerated.class.isAssignableFrom(clazz)) {
			if (!Enumerated.class.equals(clazz)) {
				List<String> states = Utils.getEnumeratedStateList((Class<? extends Enumerated>) clazz);
				if (states != null && !states.isEmpty()) {
					return Pair.of(ValueType.makeEnum(states), new Value(states.get(0)));
				} else {
					return Pair.of(ValueType.STRING, new Value(""));
				}
			} else {
				return Pair.of(ValueType.NUMBER, new Value(0));
			}
		} else if (Null.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.STRING, new Value(""));
		} else if (ObjectIdentifier.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.STRING, new Value(""));
		} else if (OctetString.class.isAssignableFrom(clazz)) {
			if (WeekNDay.class.isAssignableFrom(clazz)) {
				return Pair.of(ValueType.MAP, new Value(TypeUtils.parseWeekNDay(new WeekNDay(Month.UNSPECIFIED, WeekOfMonth.any, DayOfWeek.UNSPECIFIED))));
			} else {
				return Pair.of(ValueType.STRING, new Value(""));
			}
		} else if (Real.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.NUMBER, new Value(0));
		} else if (SignedInteger.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.NUMBER, new Value(0));
		} else if (Time.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.STRING, new Value(TypeUtils.parseTime(Time.UNSPECIFIED)));
		} else if (UnsignedInteger.class.isAssignableFrom(clazz)) {
			return Pair.of(ValueType.NUMBER, new Value(0));
		} else if (PriorityArray.class.isAssignableFrom(clazz)) {
			JsonArray ja = new JsonArray();
			for (int i=0; i<16; i++) {
				ja.add(null);
			}
			return Pair.of(ValueType.ARRAY, new Value(ja));
		} else if (clazz.getSimpleName().startsWith("Optional")) {
			if (clazz.equals(OptionalReal.class) || clazz.equals(OptionalUnsigned.class)) {
				return Pair.of(ValueType.NUMBER, null);
			} else {
				return Pair.of(ValueType.STRING, null);
			}
		} else {
			return Pair.of(ValueType.STRING, new Value(""));
		}
	}

}
