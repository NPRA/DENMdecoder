package net.gcdc.geonetworking;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;

import net.gcdc.camdenm.RunDecode;
import net.gcdc.camdenm.CoopIts.DenmInterface;
import net.gcdc.geonetworking.BasicHeader.NextHeader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/* Java and ETSI both use Big Endian. */
public class GeonetStation {

    private StationConfig                         config;

    private final static Logger logger = LoggerFactory.getLogger(GeonetStation.class);

    public final static short GN_ETHER_TYPE = (short) 0x8947;

    
    public GeonetStation(StationConfig conf) {
        config = conf;
    }



    /** Interface to lower layer (Ethernet/ITS-G5/802.11p, Link Layer) */
    public DenmInterface decodeGeoNetworking(byte[] payload) {
        logger.debug("GN Received payload of size {}", payload.length);
        // Do we want to clone payload before we start reading from it?
        ByteBuffer buffer = ByteBuffer.wrap(payload).asReadOnlyBuffer();  // I promise not to write.
        try {

            BasicHeader  basicHeader  = BasicHeader.getFrom(buffer);
            if (basicHeader.version() != config.getItsGnProtocolVersion()) {
                logger.warn("Unrecognized protocol version: {}", basicHeader.version());
                return null;
            }

            logger.info("next header: {}",NextHeader.fromValue(basicHeader.nextHeader().value()).name());
            if(basicHeader.nextHeader() == BasicHeader.NextHeader.SECURED_PACKET)
            {
                int spversion = buffer.get() & 0xff;
                logger.debug("Has secured packet version: "+spversion);
                if(spversion != 3) {
                    logger.error("Unsupported secure header version");
                    return null;
                }
                int choiceIdx = buffer.get() & 0x0f;
                int hashIdLen = buffer.get();
                int opt = buffer.get();
                int vsn2 = buffer.get();
                int choiceIdx2 = buffer.get() & 0x0f;
                int len_p = 1;
                int plen = 0;
                byte lenc = buffer.get();
                if ((lenc & 0x80) == 0x80) {
                    logger.debug("skiping multiple bytes: "+String.format(" %02x", ((lenc & 0x7f)+1)));
                    // multiple bytes
                    len_p = 1 + lenc & 0x7f;
                    for (int q=0; q<(lenc & 0x7f); q++) {
                        byte val = buffer.get();
                        logger.debug(String.format("\t %02x", val));
                        plen = plen * 256 + (buffer.get() & 0xff);
                    }
                } else {
                    // one byte.  size 0..127
                    len_p = 1;
                    plen = lenc & 0x7f;
                }
                logger.debug("    Secure(v3): vsn=" + spversion + "   Choice=" + choiceIdx + "  hashId=" + hashIdLen + "  plen=" + plen + "  opt=" + opt + "  vsn2=" + vsn2 + "   Choice2=" + choiceIdx2);
                buffer = buffer.slice(buffer.position(), len_p+plen-1);
                System.out.println(buffer.remaining());
            }
            
            CommonHeader commonHeader = CommonHeader.getFrom(buffer);

            logger.debug("common header type: {}",commonHeader.typeAndSubtype().name());

            switch (commonHeader.typeAndSubtype()) {
                case SINGLE_HOP: {
                    logger.info("Ignoring SINGLE_HOP {}",
                        commonHeader.typeAndSubtype().toString());
                    break;
                }
                case MULTI_HOP: {
                    logger.info("Ignoring MULTI_HOP {}",
                        commonHeader.typeAndSubtype().toString());
                    break;
                }
                case GEOBROADCAST_CIRCLE:
                case GEOBROADCAST_ELLIPSE:
                case GEOBROADCAST_RECTANGLE:
                case GEOANYCAST_CIRCLE:
                case GEOANYCAST_ELLIPSE:
                case GEOANYCAST_RECTANGLE:
                {
                    short sequenceNumber = buffer.getShort();
                    buffer.getShort();  // Reserved 16-bit.
                    LongPositionVector senderLpv = LongPositionVector.getFrom(buffer);
                    Area area = Area.getFrom(buffer, Area.Type.fromCode(commonHeader.typeAndSubtype().subtype()));
                    buffer.getShort();  // Reserved 16-bit.
                    byte[] upperPayload = new byte[commonHeader.payloadLength()];
                    buffer.slice().get(upperPayload, 0, commonHeader.payloadLength());

                    Destination.Geobroadcast destination = Destination.geobroadcast(area)
                            .withMaxLifetimeSeconds(basicHeader.lifetime().asSeconds())
                            .withRemainingHopLimit(basicHeader.remainingHopLimit())
                            .withMaxHopLimit(commonHeader.maximumHopLimit());
                    GeonetData indication = new GeonetData(
                            commonHeader.nextHeader(),
                            destination,
                            Optional.of(commonHeader.trafficClass()),
                            Optional.of(senderLpv),
                            upperPayload
                            );
                    PacketId packetId = new PacketId(Instant.now(), sequenceNumber, indication.sender.get().address().get());
                    logger.debug("PacketId: {}",packetId.hashCode());
                    

                    BtpPacket btpP = BtpPacket.fromGeonetData(indication);
                    
                    /*StringBuilder sb = new StringBuilder();
                    for (byte b : btpP.payload()) {
                        sb.append(String.format("%02X ", b));
                    }
                    System.out.println(sb.toString());*/
                    
                    try {
                        return RunDecode.getDecoded(btpP.payload());
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InstantiationException e) {
                        e.printStackTrace();
                    } catch (AssertionError e) {
                        e.printStackTrace();
                    }
                    break;
                }
                case BEACON: {
                    logger.info("Ignoring BEACON {}",
                            commonHeader.typeAndSubtype().toString());
                    break;
                }
                case LOCATION_SERVICE_REQUEST:
                case LOCATION_SERVICE_REPLY:
                    // Do nothing.
                    // At the moment we don't maintain Location Table.
                    logger.info("Ignoring Location Service {}",
                            commonHeader.typeAndSubtype().toString());
                    break;
                case GEOUNICAST: {
                    logger.info("Ignoring GEOUNICAST {}",
                            commonHeader.typeAndSubtype().toString());
                    break;
                }
                case ANY:
                default:
                    // Ignore for now.
                    logger.info("Ignoring {}", commonHeader.typeAndSubtype().toString());
                    break;
            }
        } catch (BufferUnderflowException | IllegalArgumentException ex) {
            logger.warn("Can't parse the packet, ignoring.", ex);
        }
        return null;
    }

    private class PacketId {
        private final Instant timestamp;
        private final Short sequenceNumber;
        private final Address sender;

        public PacketId(Instant timestamp, short sequenceNumber, Address sender) {
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
            this.sender = sender;
        }

        private GeonetStation getOuterType() {
            return GeonetStation.this;
        }

        @Override public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getOuterType().hashCode();
            result = prime * result + ((sender == null) ? 0 : sender.hashCode());
            result = prime * result + ((sequenceNumber == null) ? 0 : sequenceNumber.hashCode());
            result = prime * result + ((timestamp == null) ? 0 : timestamp.hashCode());
            return result;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            PacketId other = (PacketId) obj;
            if (!getOuterType().equals(other.getOuterType())) return false;
            if (sender == null) {
                if (other.sender != null) return false;
            } else if (!sender.equals(other.sender)) return false;
            if (sequenceNumber == null) {
                if (other.sequenceNumber != null) return false;
            } else if (!sequenceNumber.equals(other.sequenceNumber)) return false;
            if (timestamp == null) {
                if (other.timestamp != null) return false;
            } else if (!timestamp.equals(other.timestamp)) return false;
            return true;
        }
    }


    public static byte[] bytesFromHexString(String s) {
        s = s.replace(" ", "");
        if ((s.length() % 2) != 0) {
            throw new IllegalArgumentException(
                    "Converting to bytes requires even number of characters, got " + s.length());
        }
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
    
    
    static String testmsg = 
    // IVIM (should not work) "12 00 10 01 03 81 00 40 03 80 82 02 09 20 50 03 80 01 e5 01 00 14 00 00 02 03 37 42 4d f5 b8 7b ed 23 b6 79 70 06 64 24 bc 80 00 00 00 00 00 00 00 07 d4 00 00 01 04 79 ad 01 b8 00 38 16 44 59 8e 58 32 bc d6 80 dc 80 02 00 71 77 f0 f2 41 b4 05 83 30 30 43 12 76 bf a0 2c 19 01 1a 1b 93 be 94 c6 53 cc 5d 17 c9 e6 29 f2 aa 11 ea 11 ee 8f e5 08 f5 0b c5 0b c5 0b c7 43 92 85 e2 86 83 a0 2c 18 80 82 18 93 bc 3d 01 60 d8 18 10 c4 9d eb e8 0b 06 a0 a0 86 24 ed 7f 40 58 34 04 04 31 27 6e 7a 02 c1 c8 48 a3 b9 3b 21 3b 21 3b 23 d0 e4 9d 90 9d b9 e8 0b 07 01 04 8a e8 ca 14 f3 b4 f3 b7 47 f2 79 da 7b 42 7b 42 7b 43 a1 c9 3d a1 3d f3 d0 16 0d c1 c5 1d c9 d7 29 d7 29 d7 3e 87 24 eb 94 ec df 40 58 b2 0c 04 31 27 70 fa 02 c5 88 58 21 89 3b 5f d0 16 2c 02 89 15 c9 da e9 e1 29 e1 2e 8f e4 f0 94 f2 74 f2 74 f2 77 43 92 79 3a 79 df a0 2c 98 86 82 18 93 be bd 01 64 c8 38 10 c4 9d f5 e8 0b 26 61 e6 86 e4 ee 15 34 b4 ef 57 45 f2 77 aa 7a ca 7a ca 7a cb a3 f9 3d 65 3d dd 3d dd 3d dd d0 e4 9e ee 9f 16 e8 0b 26 82 06 86 e4 ee 15 34 b4 ef 57 45 f2 77 aa 7a ca 7a ca 7a cb a3 f9 3d 65 3d dd 3d dd 3d dd d0 e4 9e ee 9f 16 e8 0b 26 c2 40 86 24 f1 df 40 59 37 13 34 37 27 87 29 a5 a7 ac ba 2f 93 d6 53 ef 54 23 d4 23 dd 1f ca 11 ea 15 aa 15 aa 15 ae 87 25 0a d5 0c 17 40 59 38 14 34 37 27 87 29 a5 a7 ac ba 2f 93 d6 53 ef 54 23 d4 23 dd 1f ca 11 ea 15 aa 15 aa 15 ae 87 25 0a d5 0c 17 40 59 35 11 04 31 27 8e fa 02 cd c0 e0 21 89 3c 63 d0 16 6d c6 c1 0c 49 e0 9e 80 b3 68 30 08 62 4e f0 f4 05 9b 31 70 43 14 65 0f a0 2c db 0d 02 18 93 cb 3d 01 66 d4 64 10 c4 9d b9 e8 0b 36 42 c0 86 24 ef ff 40 59 b1 15 04 31 46 50 f8 40 01 89 00 02 07 5f da e3 45 c2 81 01 01 80 03 00 80 09 70 72 65 a6 17 74 e8 10 83 00 00 00 00 00 22 09 60 86 84 00 c8 01 0e 00 03 08 40 81 80 02 40 42 81 06 05 01 ff ff ff ff 80 03 08 40 83 81 06 05 01 ff ff ff ff 80 01 24 81 04 03 01 ff fc 80 01 25 81 05 04 01 ff ff ff 80 01 89 81 03 02 01 e0 80 01 8a 81 03 02 01 c0 80 01 8b 81 07 06 01 30 c0 01 ff f8 00 01 8d 80 01 35 81 06 05 01 ff ff ff ff 80 01 36 81 06 05 01 ff ff ff ff 80 02 02 7d 81 02 01 01 80 02 02 7e 81 05 04 01 ff ff ff 80 02 02 7f 81 05 04 01 ff ff ff 80 80 82 ca b9 05 2b b0 3c c2 d3 9f c0 e0 93 d8 d3 f9 af e8 cc 9f e7 b3 70 5e 6d 34 23 77 8c b9 b2 b8 ea 81 80 1e 8b 2b 0d a8 80 5b e1 81 8e c0 8f eb 3a ba c0 1c 8a 3f f9 b7 a5 92 f8 78 ef d0 9b 31 67 37 0f 64 ef 7c 89 1f 3e 62 19 bf 06 17 80 0d b7 e6 0c cc 4d a1 34 06 a6 85 04 fd 35 0c e2 91 41 a3 53 80 80 ea df 56 51 aa 72 c6 94 9e 21 36 29 7e 86 19 2d 4a 3a 1e ba 45 07 07 55 ea d4 e5 f4 71 c5 df 0c 82 93 a7 f0 dc 05 79 57 d3 4c a5 34 83 1b 16 68 ac c6 e7 38 52 cf 89 3e 6b 65 cc 33 ed 15 91 8d ";
    //v.2 
        "12 00 50 0a 03 81 00 40 03 80 65 20 40 01 80 00 31 0a 00 f6 d2 00 00 14 00 00 02 03 37 42 4d 38 df 6d b9 23 b6 79 70 06 64 24 bc 80 00 00 00 24 72 11 00 05 31 2d e0 00 c8 00 00 00 00 00 00 07 d2 00 00 02 01 2e 5b f2 71 81 17 2d f9 38 80 00 90 38 ec d8 01 04 19 b1 19 9c 45 a1 6f a0 07 07 af fe 0f ff ff fe 11 db ba 10 a8 c0 00 00 03 02 50 01 25 00 02 08 66 2a fa 11 8f 23 b6 79 70 06 64 24 bc 5a 3c 81 01 01 80 03 00 80 09 70 72 65 a6 17 74 e8 10 83 00 00 00 00 00 22 1a 92 76 84 00 c8 01 0e 00 03 08 40 81 80 02 40 42 81 06 05 01 ff ff ff ff 80 03 08 40 83 81 06 05 01 ff ff ff ff 80 01 24 81 04 03 01 ff fc 80 01 25 81 05 04 01 ff ff ff 80 01 89 81 03 02 01 e0 80 01 8a 81 03 02 01 c0 80 01 8b 81 07 06 01 30 c0 01 ff f8 00 01 8d 80 01 35 81 06 05 01 ff ff ff ff 80 01 36 81 06 05 01 ff ff ff ff 80 02 02 7d 81 02 01 01 80 02 02 7e 81 05 04 01 ff ff ff 80 02 02 7f 81 05 04 01 ff ff ff 80 80 83 62 ec a1 b9 80 e1 b9 89 1c 6e aa fb 11 16 14 ef fa a3 9e 9b 58 40 fe 07 8d 42 a7 d3 dd ef 10 f3 81 80 6e 6f 60 57 3c 60 23 a6 6f 65 35 52 eb cf 57 24 18 e3 f4 ae 9a 14 b2 70 37 18 45 cf 6f eb e4 b9 5f 53 b3 08 55 7e 58 0f c9 0a 26 ac bc 9f cc 97 a3 ea 77 9f b3 80 ee 6c a0 28 b2 05 c1 3c 84 06 80 80 4a 45 24 5c 08 55 f4 6b c7 ea c9 64 0e 60 74 95 3b ae 0f 26 53 9c 06 15 a9 ff d4 77 80 5b 75 20 87 9f 5c 52 5b d6 da ff fe 95 0b 81 e9 3d f1 45 c5 ff 40 b0 2e a9 b1 25 b9 cc ce f6 67 c4 14 0b ";
    //    "1200500A038100400380652040018000310A00D9010000000000020337424D9A4FC21223B67970066424BC80000000230FC84003BD522400C800000000000007D2000002012E5BF27181172DF938811790B27D2E28042B8FC845158B4B1406F072424FFFFFFE11DBBA10003C07800302500125000209E2C9C73CEA23B67970066424BC5A3C8101018003008009707265A61774E810830000000000223291EE8400C8010E00030840818002404281060501FFFFFFFF800308408381060501FFFFFFFF80012481040301FFFC80012581050401FFFFFF80018981030201E080018A81030201C080018B8107060130C001FFF800018D80013581060501FFFFFFFF80013681060501FFFFFFFF8002027D810201018002027E81050401FFFFFF8002027F81050401FFFFFF808083E4F00FE82DFF2A64C14BF3B6C169A4D7A07A8821E685B3D16A6B3AF2ED314EE481806F9C92B992A4A7C2E7A21ECDEC89B4C8A8456C3D06A756556B236656A11475175375920779293B3654332F0A0FF34FDDA54E37F791544FD5854C23F2920ABCD8808016DD9372521EA33E31B2938330EAF106644991C8255CE77D1EDAD17B81047090198F1D08B318DF1B4EA20F0A80DF63740B94A56BA351672D851A24EE1AF4C4E8";
    //v.1
    //  "12 00 50 0a 03 81 00 40 03 80 65 20 40 01 80 00 31 0a 00 ff ff 00 00 00 00 00 02 03 37 42 4d 38 e8 5b f9 23 b6 79 70 06 64 24 bc 80 00 00 00 24 72 11 00 05 31 2d e0 00 c8 00 00 00 00 00 00 07 d2 00 00 01 01 2e 5b f2 71 81 17 2d f9 38 80 00 90 38 ec d8 01 04 19 b1 19 9c 45 a1 6f a0 07 07 af fe 0f ff ff fe 11 db ba 10 a8 c0 00 00 06 04 50 01 25 00 02 08 66 4d e5 01 a7 23 b6 79 70 06 64 24 bc 5a 3c 81 01 01 80 03 00 80 09 70 72 65 a6 17 74 e8 10 83 00 00 00 00 00 22 19 b0 f6 84 00 c8 01 0e 00 03 08 40 81 80 02 40 42 81 06 05 01 ff ff ff ff 80 03 08 40 83 81 06 05 01 ff ff ff ff 80 01 24 81 04 03 01 ff fc 80 01 25 81 05 04 01 ff ff ff 80 01 89 81 03 02 01 e0 80 01 8a 81 03 02 01 c0 80 01 8b 81 07 06 01 30 c0 01 ff f8 00 01 8d 80 01 35 81 06 05 01 ff ff ff ff 80 01 36 81 06 05 01 ff ff ff ff 80 02 02 7d 81 02 01 01 80 02 02 7e 81 05 04 01 ff ff ff 80 02 02 7f 81 05 04 01 ff ff ff 80 80 82 44 d3 41 e4 7e 65 a2 b7 63 9e 08 4f 34 2b 64 e9 b4 d2 2e dc f8 40 a0 f4 d0 b9 36 67 6f 9e 81 f8 81 80 03 9f a4 19 75 42 1a 8b a0 d7 53 6c 8e 3c b0 30 35 74 31 2c ab b1 1f 26 62 be d2 05 60 ca 99 23 83 9b b5 b2 3f 98 74 ff 51 9f 7f 16 26 cb 7b b1 53 fc d1 ce dd 7e d7 6c b7 4b 97 b1 0d c0 f2 9b 80 80 ef c5 53 02 4b fc 02 05 14 79 50 11 a4 55 8c 27 7a 80 af 50 37 7f f2 18 a5 63 9e a1 df e2 1e c6 73 e4 6e 9b 63 a9 d2 ed ac 06 aa 58 fe 87 1b 33 5e 95 51 4b b5 95 bd 5a f5 10 98 0d 89 1d a4 cb ";
    //  "12 00 50 0a 03 81 00 40 03 80 65 20 40 01 80 00 31 0a 00 58 6f 00 00 14 00 00 02 03 37 42 4d e6 89 50 57 23 b6 79 70 06 64 24 bc 80 00 00 00 24 72 11 00 05 31 2d e0 00 c8 00 00 00 00 00 00 07 d2 00 00 01 01 2e 5b f2 71 81 17 2d f9 38 80 00 90 38 ec d8 01 04 19 b1 19 9c 45 a1 6f a0 07 07 af fe 0f ff ff fe 11 db ba 10 a8 c0 00 00 06 04 50 01 25 00 02 07 24 8a a1 a9 49 23 b6 79 70 06 64 24 bc 5a 3c 81 01 01 80 03 00 80 09 70 72 65 a6 17 74 e8 10 83 00 00 00 00 00 21 fb 89 db 84 00 c8 01 0e 00 03 08 40 81 80 02 40 42 81 06 05 01 ff ff ff ff 80 03 08 40 83 81 06 05 01 ff ff ff ff 80 01 24 81 04 03 01 ff fc 80 01 25 81 05 04 01 ff ff ff 80 01 89 81 03 02 01 e0 80 01 8a 81 03 02 01 c0 80 01 8b 81 07 06 01 30 c0 01 ff f8 00 01 8d 80 01 35 81 06 05 01 ff ff ff ff 80 01 36 81 06 05 01 ff ff ff ff 80 02 02 7d 81 02 01 01 80 02 02 7e 81 05 04 01 ff ff ff 80 02 02 7f 81 05 04 01 ff ff ff 80 80 82 ee 59 28 9e bf 79 ee 88 a0 40 70 83 32 ca 73 51 51 e3 3e 3e 52 33 99 e5 e1 8b 29 ff 6c b3 ba 4e 81 80 33 b3 2e 8b 01 7f 65 74 a2 0a ca d9 c2 da 62 eb 2f 9e b3 81 23 fe 99 01 ce 95 27 0e 39 f1 b9 3a 93 a5 20 cb 48 78 db 6d d1 79 02 ae 5e 7c 17 55 aa da b5 e3 3b 42 dc 2c 4a d0 56 23 b5 74 a7 78 80 80 f8 2b 39 9d 56 9e 44 25 9a 8f 33 8d dd c1 b2 0a 9d 7c ad 4b 7c c3 1f 98 9a b5 80 15 d4 7e b9 34 46 78 1b 44 b0 c1 81 81 70 fe 3f 4e 9f 76 1c 43 31 c3 45 c3 01 ce 2f 57 23 6d 68 d8 30 b9 88 47";
    //  "1200500A038100400380652040018000310A00B77D0000140000020337424DCC90C49D23B67970066424BC8000000023B504C00666FA3800C800000000000007D2000001012E5BF27181172DF93889179099916F5004266474DFC5959EDC071B0CC38FFFFFFE11DBBA10070800001804500125000206BF17C272BC23B67970066424BC5A3C8101018003008009707265A61774E81083000000000021FB89DB8400C8010E00030840818002404281060501FFFFFFFF800308408381060501FFFFFFFF80012481040301FFFC80012581050401FFFFFF80018981030201E080018A81030201C080018B8107060130C001FFF800018D80013581060501FFFFFFFF80013681060501FFFFFFFF8002027D810201018002027E81050401FFFFFF8002027F81050401FFFFFF808082EE59289EBF79EE88A040708332CA735151E33E3E523399E5E18B29FF6CB3BA4E818033B32E8B017F6574A20ACAD9C2DA62EB2F9EB38123FE9901CE95270E39F1B93A93A520CB4878DB6DD17902AE5E7C1755AADAB5E33B42DC2C4AD05623B574A778808094DC9E56214DCA7F01CA11B2FDEDAE623DBD5A8DFAA073FC24A20388751008E57508E88DAB05DFECA439BC62EB3ED24FCB6359A550A6A1143A1E806BDB67A2F7";
    //  "12 00 50 0A 03 81 00 40 03 80 65 20 40 01 80 00 31 0A 00 A1 C0 00 00 00 00 00 02 03 37 42 4D 34 0D 5C C9 23 B6 79 70 06 64 24 BC 80 00 00 00 23 B8 D9 00 06 A7 9A 78 00 C8 00 00 00 00 00 00 07 D2 00 00 02 01 2E 5B F2 71 81 17 2D F9 38 80 4A 10 6F 94 CE E0 04 1B C0 04 A1 A5 95 DC 20 07 1F 16 C7 8F FF FF FE 11 DB BA 10 A8 C0 00 00 03 02 50 01 25 00 02 08 53 56 74 8D 3C 23 B6 79 70 06 64 24 BC 5A 3C 81 01 01 80 03 00 80 09 70 72 65 A6 17 74 E8 10 83 00 00 00 00 00 22 17 B1 71 84 00 C8 01 0E 00 03 08 40 81 80 02 40 42 81 06 05 01 FF FF FF FF 80 03 08 40 83 81 06 05 01 FF FF FF FF 80 01 24 81 04 03 01 FF FC 80 01 25 81 05 04 01 FF FF FF 80 01 89 81 03 02 01 E0 80 01 8A 81 03 02 01 C0 80 01 8B 81 07 06 01 30 C0 01 FF F8 00 01 8D 80 01 35 81 06 05 01 FF FF FF FF 80 01 36 81 06 05 01 FF FF FF FF 80 02 02 7D 81 02 01 01 80 02 02 7E 81 05 04 01 FF FF FF 80 02 02 7F 81 05 04 01 FF FF FF 80 80 82 87 A2 0C 8B C7 88 6C 24 39 5D C3 7E D5 6C 0D 19 98 F7 EB 90 38 5C 58 1D 6F 67 9A 49 CE 91 BA A1 81 80 86 95 8A FE 54 94 F3 9B E8 CF C3 35 2B 05 F6 BB 8B B9 B3 3E E5 12 8F C2 11 8C 51 55 3F 3F A5 A5 4B 95 51 27 36 D5 1F 66 0B 87 73 14 F3 CA 85 D2 D7 05 4E C8 F1 2F 41 A9 99 44 6B 66 D6 26 FE B6 80 80 A3 1B 41 33 23 87 62 A0 72 A8 F7 5A 3D 7B 8E 13 8E C9 40 C0 39 BB 67 6D 6F 21 F8 31 41 E2 90 E5 12 F1 6C 87 2D 78 D4 CC AB 8F 30 BB AF D6 7A 83 6D 10 A1 70 50 CD 74 86 F7 CE 7A C7 A4 49 12 CE";

    public static void main(String[] args) {

        StationConfig conf = new StationConfig();
        conf.setItsGnProtocolVersion(1);
        GeonetStation station = new GeonetStation(conf);
        // denminterface could be either of type denm.class or denm2.class depending on if it is protocol version 1 or 2 
        DenmInterface decodedDenm = station.decodeGeoNetworking(GeonetStation.bytesFromHexString(GeonetStation.testmsg));
        System.out.println(decodedDenm);
        System.out.println(decodedDenm.toJson());
    }

}
