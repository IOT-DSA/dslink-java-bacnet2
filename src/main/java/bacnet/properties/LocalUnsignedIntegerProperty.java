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
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

public class LocalUnsignedIntegerProperty extends LocalBacnetProperty {
	
	private boolean isEnum;
	
	public LocalUnsignedIntegerProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalUnsignedIntegerProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node, boolean useDescriptions){
		super(oid, pid, point, parent, node);
		this.isEnum = useDescriptions;
		
		bacnetObj.writeProperty(pid, new UnsignedInteger(0));
		if (useDescriptions && this.bacnetPoint.getUnitsDescription().size() > 0) {
			node.setValueType(ValueType.makeEnum(this.bacnetPoint.getUnitsDescription()));
			node.setValue(new Value(this.bacnetPoint.getUnitsDescription().get(0)));
		} else {
			node.setValueType(ValueType.NUMBER);
			node.setValue(new Value(0));
		}
		;
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}
	
	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			set(newVal);
		}
	}
	
	public void set(Value newVal) {
		Number num = newVal.getNumber();
		String str = newVal.getString();
		
		if (num == null) {
			num = bacnetPoint.getUnitsDescription().indexOf(str);
		}
		
		if (num.intValue() != -1) {
			bacnetObj.writeProperty(propertyId, new UnsignedInteger(num.intValue()));
			node.setAttribute(propertyId.toString(), newVal);
		}
	}
	
	public void update() {
		if (isEnum && this.bacnetPoint.getUnitsDescription().size() > 0) {
			node.setValueType(ValueType.makeEnum(this.bacnetPoint.getUnitsDescription()));
		}
	}

	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof UnsignedInteger) {
			int n = ((UnsignedInteger) enc).intValue();
			if (!isEnum) {
				node.setValue(new Value(n));
			} else {
				if (this.bacnetPoint.getUnitsDescription().size() > n) {
					node.setValue(new Value(this.bacnetPoint.getUnitsDescription().get(n)));
				}
			}
		}
		
	}
}
