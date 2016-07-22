package bacnet.properties;

import java.util.ArrayList;
import java.util.List;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.EngineeringUnits;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;

public class LocalUnitsProperty extends LocalBacnetProperty {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	EngineeringUnits units;

	public LocalUnitsProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}

	public LocalUnitsProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent,
			Node node) {
		super(oid, pid, point, parent, node);

		bacnetObj.writeProperty(PropertyIdentifier.units, EngineeringUnits.degreeDaysCelsius);
		node.setValueType(ValueType.makeEnum(enumeratedNames()));
		node.setValue(new Value(EngineeringUnits.degreeDaysCelsius.toString()));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}

	private List<String> enumeratedNames() {
		List<String> lst = new ArrayList<String>();
		for (EngineeringUnits u : EngineeringUnits.ALL) {
			lst.add(u.toString());
		}
		return lst;
	}

	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource())
				return;
			Value newVal = event.getCurrent();
			units = parseEngineeringUnits(newVal.getString());
			bacnetObj.writeProperty(propertyId, units);
			node.setAttribute(propertyId.toString(), newVal);
		}
	}

	protected EngineeringUnits parseEngineeringUnits(String unitsString) {

		for (EngineeringUnits unit : EngineeringUnits.ALL) {
			if (unit.toString().equals(unitsString)) {
				return unit;
			}
		}

		return null;
	}

	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof EngineeringUnits) {
			units = (EngineeringUnits) enc;
			node.setValue(new Value(units.toString()));
		}

	}
}
