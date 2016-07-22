package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;

import bacnet.LocalBacnetPoint;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;

public class LocalRealProperty extends LocalBacnetProperty {
	
	float value;
	
	public LocalRealProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalRealProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(pid, new Real(0));
		node.setValueType(ValueType.NUMBER);
		node.setValue(new Value(0));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}
	
	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			value = newVal.getNumber().floatValue();		
			bacnetObj.writeProperty(propertyId, new Real(value));		
			node.setAttribute(propertyId.toString(), newVal);
		}
	}

	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof Real) {
			value = ((Real) enc).floatValue();
			node.setValue(new Value(value));
		}
	}
}
