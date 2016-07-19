package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;

import bacnet.LocalBacnetPoint;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class LocalBinaryPVProperty extends LocalBacnetProperty {
	
	boolean state;

	public LocalBinaryPVProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalBinaryPVProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node, boolean useDescriptions){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(pid, BinaryPV.active);
		if (useDescriptions && this.bacnetPoint.getUnitsDescription().size() >= 2) {
			String inact = this.bacnetPoint.getUnitsDescription().get(0);
			String act = this.bacnetPoint.getUnitsDescription().get(1);
			node.setValueType(ValueType.makeBool(act, inact));
		} else {
			node.setValueType(ValueType.BOOL);
		}
		node.setValue(new Value(true));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}
	
	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			state = newVal.getBool();
			
			bacnetObj.writeProperty(propertyId, state ? BinaryPV.active : BinaryPV.inactive);
			
			node.setAttribute(propertyId.toString(), newVal);
		}
	}
	
	public void update() {
		if (this.bacnetPoint.getUnitsDescription().size() >= 2) {
			String inact = this.bacnetPoint.getUnitsDescription().get(0);
			String act = this.bacnetPoint.getUnitsDescription().get(1);
			node.setValueType(ValueType.makeBool(act, inact));
		}
	}
	
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof BinaryPV) {
			state = enc.equals(BinaryPV.active);
			node.setValue(new Value(state));
		}
	}
	
}
