package bacnet.properties;

import java.util.ArrayList;
import java.util.List;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;

import bacnet.LocalBacnetPoint;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

public class LocalStateTextProperty extends LocalBacnetProperty {

	public LocalStateTextProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}

	public LocalStateTextProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent,
			Node node) {
		super(oid, pid, point, parent, node);

		node.setValueType(ValueType.ARRAY);
		node.setValue(new Value(new JsonArray()));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}

	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource())
				return;
			Value newVal = event.getCurrent();
			set(newVal);
		}
	}

	public void set(Value newVal) {
		JsonArray jsonArray = newVal.getArray();

		List<CharacterString> states = new ArrayList<CharacterString>();
		for (Object o : jsonArray) {
			if (o instanceof String) {
				states.add(new CharacterString((String) o));
			}
		}

		if (states.isEmpty())
			return;

		bacnetObj.writeProperty(propertyId, new SequenceOf<CharacterString>(states));
		node.setAttribute(propertyId.toString(), newVal);

		for (int i = 0; i < states.size(); i++) {
			if (bacnetPoint.getUnitsDescription().size() < i + 1) {
				bacnetPoint.getUnitsDescription().add(states.get(i).getValue());
			} else {
				bacnetPoint.getUnitsDescription().set(i, states.get(i).getValue());
			}
		}
		LocalBacnetProperty pvalProp = bacnetPoint.getProperty(PropertyIdentifier.presentValue);
		if (pvalProp instanceof LocalUnsignedIntegerProperty) {
			((LocalUnsignedIntegerProperty) pvalProp).update();
		}
		LocalBacnetProperty relinqProp = bacnetPoint.getProperty(PropertyIdentifier.relinquishDefault);
		if (relinqProp instanceof LocalUnsignedIntegerProperty) {
			((LocalUnsignedIntegerProperty) relinqProp).update();
		}
		LocalBacnetProperty nosProp = bacnetPoint.getProperty(PropertyIdentifier.numberOfStates);
		if (nosProp instanceof LocalNumberOfStatesProperty) {
			if (((LocalNumberOfStatesProperty) nosProp).value != states.size()) {
				nosProp.node.setValue(new Value(states.size()));
				((LocalNumberOfStatesProperty) nosProp).set(new Value(states.size()));
			}
		}

	}

	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof SequenceOf<?>) {
			JsonArray jarr = new JsonArray();
			for (Encodable e : (SequenceOf<?>) enc) {
				if (e instanceof CharacterString) {
					jarr.add(((CharacterString) e).getValue());
				}
			}
			node.setValue(new Value(jarr));
		}

	}

}
