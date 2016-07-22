package bacnet.properties;

import java.util.Arrays;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;

import bacnet.LocalBacnetPoint;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.Polarity;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class LocalPolarityProperty extends LocalBacnetProperty {
	
	static final String ATTRIBUTE_POLARITY = "Polarity";
	
	Polarity polarity;
	
	public LocalPolarityProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalPolarityProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(PropertyIdentifier.polarity, Polarity.normal);
		node.setValueType(ValueType.makeEnum(enumeratedNames()));
		node.setValue(new Value(Polarity.normal.toString()));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}
	

	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			polarity = parsePolarity(newVal.getString());	
			bacnetObj.writeProperty(PropertyIdentifier.polarity, polarity);	
			node.setAttribute(ATTRIBUTE_POLARITY, newVal);
		}
	}
	
	private  String[] enumeratedNames(){
		String valuesStr = Arrays.toString(Polarity.ALL);
		return valuesStr.substring(1, valuesStr.length() - 1).replace(" ", "").split(",");
	}
	
	protected Polarity parsePolarity(String pString) {

		for (Polarity p : Polarity.ALL){
			if(p.toString().equals(pString)){
				return p;
			}
		}

		return null;
	}

	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof Polarity) {
			polarity = (Polarity) enc;
			node.setValue(new Value(polarity.toString()));
		}
	}
}
