#include <functional>
#include <queue>
#include <algorithm>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/fcntl.h>
#include <unistd.h>
#include <omp.h>
#include <cstdlib>
#include <arpa/inet.h>

#define IGNORE_PRINTF

#ifdef IGNORE_PRINTF
#define printf(fmt, ...) (0)
#endif

// param: link latency in cycles
// assuming 3.2 GHz, this number / 3.2 = link latency in ns
// e.g. setting this to 35000 gives you 35000/3.2 = 10937.5 ns latency
// IMPORTANT: this must be a multiple of 7
//
// THIS IS SET BY A COMMAND LINE ARGUMENT. DO NOT CHANGE IT HERE.
//#define LINKLATENCY 6405
int LINKLATENCY = 0;

// param: switching latency in cycles
// assuming 3.2 GHz, this number / 3.2 = switching latency in ns
//
// THIS IS SET BY A COMMAND LINE ARGUMENT. DO NOT CHANGE IT HERE.
int switchlat = 0;

#define SWITCHLATENCY (switchlat)

// param: numerator and denominator of bandwidth throttle
// Used to throttle outbound bandwidth from port
//
// THESE ARE SET BY A COMMAND LINE ARGUMENT. DO NOT CHANGE IT HERE.
int throttle_numer = 1;
int throttle_denom = 1;

// uncomment to use a limited output buffer size, OUTPUT_BUF_SIZE
//#define LIMITED_BUFSIZE

// size of output buffers, in # of flits
// only if LIMITED BUFSIZE is set
// TODO: expose in manager
#define OUTPUT_BUF_SIZE (131072L)

// pull in # clients config
#define NUMCLIENTSCONFIG
#include "switchconfig.h"
#undef NUMCLIENTSCONFIG

// DO NOT TOUCH
#define NUM_TOKENS (LINKLATENCY)
#define TOKENS_PER_BIGTOKEN (7)
#define BIGTOKEN_BYTES (64)
#define NUM_BIGTOKENS (NUM_TOKENS/TOKENS_PER_BIGTOKEN)
#define BUFSIZE_BYTES (NUM_BIGTOKENS*BIGTOKEN_BYTES)

// DO NOT TOUCH
#define SWITCHLAT_NUM_TOKENS (SWITCHLATENCY)
#define SWITCHLAT_NUM_BIGTOKENS (SWITCHLAT_NUM_TOKENS/TOKENS_PER_BIGTOKEN)
#define SWITCHLAT_BUFSIZE_BYTES (SWITCHLAT_NUM_BIGTOKENS*BIGTOKEN_BYTES)

uint64_t this_iter_cycles_start = 0;

// pull in mac2port array
#define MACPORTSCONFIG
#include "switchconfig.h"
#undef MACPORTSCONFIG

#include "flit.h"
#include "baseport.h"
#include "shmemport.h"
#include "socketport.h"
#include "sshport.h"

// TODO: replace these port mapping hacks with a mac -> port mapping,
// could be hardcoded

BasePort * ports[NUMPORTS];

/* Xingyu: switch from input ports to output ports */
void do_fast_switching_xingyu() {
// Set up send buffs: 
    #pragma omp parallel for
    for (int port = 0; port < NUMPORTS; port++) {
        ports[port]->setup_send_buf();
    }

    #pragma omp parallel for
    for (int port = 0; port < NUMPORTS; port++) {
        BasePort * current_port = ports[port];
        uint8_t * input_port_buf = current_port->current_input_buf;
        for (int tokenno = 0; tokenno < (NUM_TOKENS / 7) * 8; tokenno++) {
            // Get a 64-bit/ 8-byte value
            uint64_t flit = *(((uint64_t*)input_port_buf) + tokenno);
            // fprintf(stdout, "flit code: %x \n", flit);
            switchpacket * sp;
            if (!(current_port->input_in_progress)) {
                
                sp = (switchpacket*)calloc(sizeof(switchpacket), 1);
                current_port->input_in_progress = sp;

                // here is where we inject switching latency. this is min port-to-port latency
                sp->timestamp = this_iter_cycles_start + tokenno/8 + SWITCHLATENCY;
                sp->sender = port;
            }
            sp = current_port->input_in_progress;
            sp->dat[sp->amtwritten++] = flit;

            // Completing this switch packet
            if (sp->amtwritten >= 8 || tokenno == ((NUM_TOKENS / 7) * 8 - 1) ) {
                current_port->input_in_progress = NULL;
                current_port->inputqueue.push(sp);
                fprintf(stdout, "A new switch packet from port %d: ", port);
                for (int j = 0; j < sp->amtwritten; j++) {
                    fprintf(stdout, "%x ", sp->dat[j]);
                }
                fprintf(stdout, "\n");
                // fprintf(stdout, "packet timestamp: %ld, len: %ld, sender: %d\n", this_iter_cycles_start + (tokenno - 7) / 8, sp->amtwritten, port);
            }
        }
    }

    fprintf(stdout, "The size of port 0 input queue is %d \n", ports[0]->inputqueue.size());
    fprintf(stdout, "The size of port 1 input queue is %d \n", ports[1]->inputqueue.size());

    // next do the switching. but this switching is just shuffling pointers,
    // so it should be fast. it has to be serial though...

    // NO PARALLEL!
    // shift pointers to output queues, but in order. basically.
    // until the input queues have no more complete packets
    // 1) find the next switchpacket with the lowest timestamp across all the inputports
    // 2) look at its mac, copy it into the right ports
    //     i) if it's a broadcast: sorry, you have to make N-1 copies of it...
    //     to put into the other queues

    struct tspacket {
        uint64_t timestamp;
        switchpacket * switchpack;

        bool operator<(const tspacket &o) const
        {
            return timestamp > o.timestamp;
        }
    };
    typedef struct tspacket tspacket;

    std::priority_queue<tspacket> pqueue; 

    fprintf(stdout, "starting switching\n");

    for (int i = 0; i < NUMPORTS; i++) {
        while (!(ports[i]->inputqueue.empty())) {
            switchpacket * sp = ports[i]->inputqueue.front();
            ports[i]->inputqueue.pop();
            pqueue.push( tspacket { sp->timestamp, sp });
        }
    }

    fprintf(stdout, "The size of input priority queue is %d \n", pqueue.size());

    // next, put back into individual output queues
    while (!pqueue.empty()) {
        switchpacket * tsp = pqueue.top().switchpack;
        pqueue.pop();
        uint16_t send_to_port = 1 - tsp->sender;
        ports[send_to_port]->outputqueue.push(tsp);
        // fprintf(stdout, "At timestamps %d, %d packets are sent from port %d to port %d \n", tsp->timestamp, tsp->amtwritten, tsp->sender, send_to_port);
    }

    fprintf(stdout, "The size of port 0 output queue is %d \n", ports[0]->outputqueue.size());
    fprintf(stdout, "The size of port 1 output queue is %d \n", ports[1]->outputqueue.size());

    // finally in parallel, flush whatever we can to the output queues based on timestamp
    #pragma omp parallel for
    for (int port = 0; port < NUMPORTS; port++) {
        BasePort * thisport = ports[port];         

        // Initialize constraints variables
        uint64_t flitswritten = 0;
        bool empty_buf = true;

        fprintf(stdout, "Start printing output queue port %d\n", port);

        while (!(thisport->outputqueue.empty())) {
            switchpacket *thispacket = thisport->outputqueue.front();
            fprintf(stdout, "Inserting a new packet: ");
            for (int i = 0; i < thispacket->amtwritten; i++) {
                empty_buf = false;
                *(((uint64_t*)thisport->current_output_buf) + flitswritten) = thispacket->dat[i];
                flitswritten += 1;
                fprintf(stdout, "%x ", thispacket->dat[i]);
            }
            fprintf(stdout, "\n");
            thisport->outputqueue.pop();
            free(thispacket); 
        }
        fprintf(stdout, "flits written in port %d is %d \n", port, flitswritten);
        if (empty_buf) {
            ((uint64_t*)thisport->current_output_buf)[0] = 0xDEADBEEFDEADBEEFL;
        }
    }
}





/* switch from input ports to output ports */
void do_fast_switching() {
#pragma omp parallel for
    for (int port = 0; port < NUMPORTS; port++) {
        ports[port]->setup_send_buf();
    }


// preprocess from raw input port to packets
#pragma omp parallel for
for (int port = 0; port < NUMPORTS; port++) {
    BasePort * current_port = ports[port];
    uint8_t * input_port_buf = current_port->current_input_buf;
    fprintf(stdout, "Working on Port %d\n", port);
    for (int tokenno = 0; tokenno < NUM_TOKENS / 7 * 8; tokenno++) {
        //fprintf(stdout, "is there a valid flit: %d\n", is_valid_flit(input_port_buf, tokenno));
        if (true || is_valid_flit(input_port_buf, tokenno)) {
            uint64_t flit = get_flit(input_port_buf, tokenno);
            // fprintf(stdout, "flit code: %d\n", flit);
            switchpacket * sp;
            if (!(current_port->input_in_progress)) {
                sp = (switchpacket*)calloc(sizeof(switchpacket), 1);
                current_port->input_in_progress = sp;

                // here is where we inject switching latency. this is min port-to-port latency
                sp->timestamp = this_iter_cycles_start + tokenno + SWITCHLATENCY;
                sp->sender = port;
            }
            sp = current_port->input_in_progress;

            sp->dat[sp->amtwritten++] = flit;
            if (true || (tokenno % 8 == 7) || is_last_flit(input_port_buf, tokenno)) {
                current_port->input_in_progress = NULL;
                fprintf(stdout, "A switch packet is processed\n");
                fprintf(stdout, "packet timestamp: %ld, len: %ld, sender: %d\n", this_iter_cycles_start + tokenno, sp->amtwritten, port);
                if (current_port->push_input(sp)) {
                    printf("packet timestamp: %ld, len: %ld, sender: %d\n",
                            this_iter_cycles_start + tokenno,
                            sp->amtwritten, port);
                }
            }
        }
    }
}
fprintf(stdout, "done preprocessing\n");
// next do the switching. but this switching is just shuffling pointers,
// so it should be fast. it has to be serial though...

// NO PARALLEL!
// shift pointers to output queues, but in order. basically.
// until the input queues have no more complete packets
// 1) find the next switchpacket with the lowest timestamp across all the inputports
// 2) look at its mac, copy it into the right ports
//          i) if it's a broadcast: sorry, you have to make N-1 copies of it...
//          to put into the other queues

struct tspacket {
    uint64_t timestamp;
    switchpacket * switchpack;

    bool operator<(const tspacket &o) const
    {
        return timestamp > o.timestamp;
    }
};

typedef struct tspacket tspacket;


// TODO thread safe priority queue? could do in parallel?
std::priority_queue<tspacket> pqueue;
fprintf(stdout, "starting switching\n");
for (int i = 0; i < NUMPORTS; i++) {
    fprintf(stdout, "Port %d is empty: %d\n", i, ports[i]->inputqueue.empty());
    while (!(ports[i]->inputqueue.empty())) {
        switchpacket * sp = ports[i]->inputqueue.front();
        ports[i]->inputqueue.pop();
        pqueue.push( tspacket { sp->timestamp, sp });
    }
}

// next, put back into individual output queues
while (!pqueue.empty()) {
    switchpacket * tsp = pqueue.top().switchpack;
    pqueue.pop();
    uint16_t send_to_port = 1 - tsp->sender;
    fprintf(stdout, "packet for port: %x\n", send_to_port);
    fprintf(stdout, "packet from port: %x\n", tsp->sender);
    fprintf(stdout, "packet timestamp: %ld\n", tsp->timestamp);
    if (send_to_port == BROADCAST_ADJUSTED) {
#define ADDUPLINK (NUMUPLINKS > 0 ? 1 : 0)
        // this will only send broadcasts to the first (zeroeth) uplink.
        // on a switch receiving broadcast packet from an uplink, this should
        // automatically prevent switch from sending the broadcast to any uplink
        for (int i = 0; i < NUMDOWNLINKS + ADDUPLINK; i++) {
            if (i != tsp->sender ) {
                switchpacket * tsp2 = (switchpacket*)malloc(sizeof(switchpacket));
                memcpy(tsp2, tsp, sizeof(switchpacket));
                ports[i]->outputqueue.push(tsp2);
            }
        }
        free(tsp);
    } else {
        ports[send_to_port]->outputqueue.push(tsp);
    }
}


// finally in parallel, flush whatever we can to the output queues based on timestamp

#pragma omp parallel for
for (int port = 0; port < NUMPORTS; port++) {
    BasePort * thisport = ports[port];
    thisport->write_flits_to_output();
}

}

static void simplify_frac(int n, int d, int *nn, int *dd)
{
    int a = n, b = d;

    // compute GCD
    while (b > 0) {
        int t = b;
        b = a % b;
        a = t;
    }

    *nn = n / a;
    *dd = d / a;
}

int main (int argc, char *argv[]) {
    int bandwidth;

    if (argc < 4) {
        // if insufficient args, error out
        fprintf(stdout, "usage: ./switch LINKLATENCY SWITCHLATENCY BANDWIDTH\n");
        fprintf(stdout, "insufficient args provided\n.");
        fprintf(stdout, "LINKLATENCY and SWITCHLATENCY should be provided in cycles.\n");
        fprintf(stdout, "BANDWIDTH should be provided in Gbps\n");
        exit(1);
    }

    LINKLATENCY = atoi(argv[1]);
    switchlat = atoi(argv[2]);
    bandwidth = atoi(argv[3]);

    simplify_frac(bandwidth, 200, &throttle_numer, &throttle_denom);

    fprintf(stdout, "Using link latency: %d\n", LINKLATENCY);
    fprintf(stdout, "Using switching latency: %d\n", SWITCHLATENCY);
    fprintf(stdout, "BW throttle set to %d/%d\n", throttle_numer, throttle_denom);
    fprintf(stdout, "Number of ports: %d\n", NUMPORTS);
    if ((LINKLATENCY % 7) != 0) {
        // if invalid link latency, error out.
        fprintf(stdout, "INVALID LINKLATENCY. Currently must be multiple of 7 cycles.\n");
        exit(1);
    }

//    omp_set_num_threads(NUMPORTS); // we parallelize over ports, so max threads = # ports
    omp_set_num_threads(1);

#define PORTSETUPCONFIG
#include "switchconfig.h"
#undef PORTSETUPCONFIG

    while (true) {

        // handle sends
#pragma omp parallel for
        for (int port = 0; port < NUMPORTS; port++) {
            ports[port]->send();
        }

        // handle receives. these are blocking per port
#pragma omp parallel for
        for (int port = 0; port < NUMPORTS; port++) {
            ports[port]->recv();
        }
 
#pragma omp parallel for
        for (int port = 0; port < NUMPORTS; port++) {
            ports[port]->tick_pre();
        }
        
        fprintf(stdout, "Entering fast switching\n");
//        do_fast_switching();
        do_fast_switching_xingyu();

        this_iter_cycles_start += LINKLATENCY / 7; // keep track of time
        fprintf(stdout, "Cycles: %d\n", this_iter_cycles_start);
        // some ports need to handle extra stuff after each iteration
        // e.g. shmem ports swapping shared buffers
#pragma omp parallel for
        for (int port = 0; port < NUMPORTS; port++) {
            ports[port]->tick();
        }

    }
}
