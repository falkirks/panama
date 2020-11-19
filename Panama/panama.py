import zmq
import time
import sys
import json
import threading 
import argparse
import subprocess
import pickle
import zlib

# Set to true for verbose execution 
debug = True

# ZeroMQ context allows socket creation
context = zmq.Context()

# The clients are stored with each client ID as a key to a 
# boolean. The bool represents whether or not the client is auditing. 
clients = {}

# Audit port is where the leader broadcasts audit requests 
# to all of the clients. In PUB/SUB pattern where leader is PUB
audit_port = "5557"

# Flag for which version of panama to run
is_leader = False

# The IP address to connect to
ip_address = "127.0.0.1"

def get_audit_log():
    log_data = None 
    try:
        with open("/tmp/audit.log") as audit_log:
            log_data = audit_log.read()
    except:
        log_data = None
    return(log_data)

# This function sits and waits on the connect port
# any message that comes to the leader is processed here
# It is run in a new thread 
def process_follower_msgs():
    context = zmq.Context.instance()
    # This counter is how followers get unique IDs
    # each follower gets current val than it is incremented
    follower_counter = 0

    # Binds to REP socket
    socket = context.socket(zmq.REP)
    socket.bind("tcp://*:%s" % connect_port)

    # Do this until program is closed
    while True:
        #  Wait for next request from client
        message = pickle.loads(socket.recv())
        if(debug): print("Received request: ", message)

        # Messages will have a topic key indicating what it is for
        if(message["topic"] == "connect"): # New follower trying to connect
            # Grab counter value and increment
            new_id = str(follower_counter)
            follower_counter += 1
            
            # Construct message to send back, telling the follower its
            # ID and which port to connect for audit requests
            message = {"ID": new_id, "audit_port": audit_port}
            message = pickle.dumps(message)
            socket.send(message)
            
            # Add client to dict and continue
            clients[str(new_id)] = False
            if(debug): print("assigned ", str(new_id))

        elif (message["topic"] == "audit"): # Follower changing audit state
            clients[message["ID"]] = message["current_state"]
            if("data" in message.keys() and message["data"] is not None):
                with open("audits/audit" + str(message["ID"]) + ".log", "w+") as new_log:
                    new_log.write(zlib.decompress(message["data"]).decode("utf-8"))
            socket.send_string("ACK")
        else:
            pass


def run_leader(connect_port):

    # context creates sockets
    context = zmq.Context.instance()

    # Audit port is where the leader broadcasts audit requests 
    # to all of the clients. In PUB/SUB pattern where leader is PUB
    audit_port = "5557"


    # Run follower process function as daemon so it will stop when 
    # program ends
    ids = threading.Thread(target=process_follower_msgs, daemon=True)
    ids.start()

    # Bind to audit socket
    audit_socket = context.socket(zmq.PUB)
    audit_socket.bind("tcp://*:%s" % audit_port)

    # Simple CLI to interact with leader 
    print("Type help for options")
    while True:
        val = input("> ") 
        if(val == "quit" or val =="q"):
            audit_socket.send_string("STOP")
            quit()
        elif(val == "help" or val == "h"):
            print("type s(tart) to begin an audit")
            print("Type q(uit) to close")
        elif(val == "start" or val == "s"):
            if(debug): print("starting audit")
            audit_socket.send_string("START")
        elif(val == "end" or val == "e"):
            if(debug): print("stopping audit")
            audit_socket.send_string("END")
        elif(val =="d" or val == "debug"):
            print(clients)

def run_follower(connect_port):

    # ZeroMQ context allows socket creation
    context = zmq.Context.instance()

    # Connect to leader socket
    if(debug): print("Connecting to leader...")
    socket = context.socket(zmq.REQ)
    socket.connect("tcp://" + ip_address + ":%s" % connect_port)

    # Attempt to register with the leader
    if(debug): print("Sending connect request...")
    connect_message = {"topic":"connect"}
    socket.send(pickle.dumps(connect_message))

    #  Get the reply with ID and audit port
    client_info = socket.recv()
    client_info = pickle.loads(client_info)
    if(debug): print("Received ID [", client_info["ID"], "]")

    # Connect to the audit port received from leader
    audit_socket = context.socket(zmq.SUB)
    audit_socket.connect("tcp://" + ip_address + ":%s" % client_info["audit_port"])
    audit_socket.setsockopt(zmq.SUBSCRIBE, b'')

    # Until the program ends wait for messages from leader
    while True:
        # Block on audit socket
        audit_message = audit_socket.recv()
        if(debug): print(audit_message)


        if (audit_message == b'STOP'): # If leader is killing program break out of loop
            quit()
        elif (audit_message == b"START"): # Start an audit
            if(debug): print("starting audit")

            #TODO start CamFlow
            cf =subprocess.run(["camflow", "-e", "true"])
            cf =subprocess.run(["camflow", "-a", "true"])

            # Tell leader camflow has started
            audit_start = {"topic":"audit","ID":client_info["ID"], "current_state": True}
            socket.send(pickle.dumps(audit_start))

            # MUST receive ack for this socket pattern
            ack = socket.recv()
            if(debug): print(ack)
        elif (audit_message == b"END"):
            if(debug):print("stopping audit")
            
            # TODO stop CamFlow
            cf =subprocess.run(["camflow", "-e", "false"])
            cf =subprocess.run(["camflow", "-a", "false"])
            if(debug):print("audit stopped")
            log_data = get_audit_log()
            if(log_data is not None):
                audit_msg ={"topic":"audit", "ID":client_info["ID"],\
                        "current_state": False, "data":zlib.compress(log_data.encode("utf-8"))}
                audit_msg = pickle.dumps(audit_msg)
                socket.send(audit_msg)
            else:
                print("No log file")
                # Prepare and send message for leader indicating state change
                audit_stop = {"topic":"audit","ID":client_info["ID"],\
                        "current_state": False, "data": None}
                socket.send(pickle.dumps(audit_stop))
            
            # MUST receive ack for this socket pattern
            ack = socket.recv()
            if(debug):print(ack)

            # TODO send audit log 

if __name__ == "__main__":
    # This port is how the follower / leader talk
    connect_port = "5556"
    parser = argparse.ArgumentParser()
   
    parser.add_argument('-l', '--leader', action='store_true')
    parser.add_argument('-a', '--address')
    args = parser.parse_args()

    if args.leader:
        is_leader = True
    if args.address:
        ip_address = args.address
    
    while True:
        if(is_leader):
            run_leader(connect_port)
        else:
            run_follower(connect_port)
    
    


    
