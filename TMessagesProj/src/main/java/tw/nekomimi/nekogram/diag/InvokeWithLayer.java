package tw.nekomimi.nekogram.diag;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * invokeWithLayer#da9b0d0d layer:int query:!X = X — runs one request as an older protocol
 * version would send it (other clients such as Plus Messenger use older layers).
 */
public class InvokeWithLayer extends TLObject {
    public static final int constructor = 0xda9b0d0d;

    public int layer;
    public TLObject query;

    public InvokeWithLayer(int layer, TLObject query) {
        this.layer = layer;
        this.query = query;
    }

    @Override
    public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
        // Older layers answer joinChannel with Updates; newer ones with ChatInviteJoinResult.
        final TLObject updates = TLRPC.Updates.TLdeserialize(stream, constructor, false);
        if (updates != null) {
            return updates;
        }
        return query.deserializeResponse(stream, constructor, exception);
    }

    @Override
    public void serializeToStream(OutputSerializedData stream) {
        stream.writeInt32(constructor);
        stream.writeInt32(layer);
        query.serializeToStream(stream);
    }
}
