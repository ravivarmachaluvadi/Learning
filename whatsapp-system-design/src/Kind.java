/**
 * Part of the wire vocabulary of our toy chat system.
 * Everything that moves between a phone and a server, or between two servers,
 * is a Packet; everything a human typed is a Msg carried inside a Packet.
 */
enum Kind {
    /** phone -> its connection server: "please deliver this message" */
    SEND,
    /** connection server -> sender: "I have durably stored it"  (one grey tick) */
    SERVER_ACK,
    /** connection server -> recipient device: "here is a message for you" */
    DELIVER,
    /** recipient device -> its connection server: "I wrote it to my disk" */
    DEVICE_ACK,
    /** connection server -> sender: "the recipient's device has it" (two grey ticks) */
    DELIVERED_RECEIPT,
    /** recipient device -> ... -> sender: "the human opened the chat" (two blue ticks) */
    READ_RECEIPT,
    /** phone -> server: "I am online, my socket lives on you now" */
    CONNECT,
    /** server -> server: "you hold this device's socket, hand this to it" */
    ROUTE
}
