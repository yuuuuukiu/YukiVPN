import socket

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 1701))

while True:
    payload, peer = sock.recvfrom(65535)
    sock.sendto(payload, peer)
