//See LICENSE for license details
#ifdef SIMPLENICBRIDGEMODULE_struct_guard

#include "simplenic.h"

#include <stdio.h>
#include <string.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>

// DO NOT MODIFY PARAMS BELOW THIS LINE
#define TOKENS_PER_BIGTOKEN 7

#define SIMLATENCY_BT (this->LINKLATENCY/TOKENS_PER_BIGTOKEN)

#define BUFWIDTH (512/8)
#define BUFBYTES (SIMLATENCY_BT*BUFWIDTH)
#define EXTRABYTES 1

#define FLIT_BITS 64
#define PACKET_MAX_FLITS 190
#define BITTIME_PER_QUANTA 512
#define CYCLES_PER_QUANTA (BITTIME_PER_QUANTA / FLIT_BITS)

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

#define niclog_printf(...) if (this->niclog) { fprintf(this->niclog, __VA_ARGS__); fflush(this->niclog); }

simplenic_t::simplenic_t(simif_t *sim, std::vector<std::string> &args,
        SIMPLENICBRIDGEMODULE_struct *mmio_addrs, int simplenicno,
        long dma_addr): bridge_driver_t(sim)
{
    this->mmio_addrs = mmio_addrs;
    //this->direction = read(this->mmio_addrs->direction);
    printf("Linklatency is %d\n", this->LINKLATENCY);
    this->LINKLATENCY = 6405;
    this->dma_addr = dma_addr;
    int shmemfd;
    for(int i = 0; i < 2; i++) {
        std::string name = "filler";
        printf("opening shmem region\n%s\n", name.c_str());
        shmemfd = shm_open(name.c_str(), O_RDWR|O_CREAT, S_IRWXU);
        ftruncate(shmemfd, BUFBYTES+EXTRABYTES);
        printf("Size of pcis_read_bufs: %d\n", BUFBYTES+EXTRABYTES);
        pcis_read_bufs[i] = (char *) mmap(NULL, BUFBYTES+EXTRABYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shmemfd, 0);//remember to initialize in the write direction as well later
        //WILL NEED TO ADJUST BUFFERS DEPENDING ON DIRECTION AT SOME POINT
    }
    printf("successful init\n");
}

simplenic_t::~simplenic_t() {
    for (int i = 0; i < 2; i++) {
        munmap(pcis_read_bufs[i], BUFBYTES+EXTRABYTES);
    }
    free(this->mmio_addrs);
}

#define ceil_div(n, d) (((n) - 1) / (d) + 1)

void simplenic_t::init() {
    /*uint32_t output_tokens_available = read(mmio_addrs->outgoing_count);
    uint32_t input_token_capacity = SIMLATENCY_BT - read(mmio_addrs->incoming_count);
    if ((input_token_capacity != SIMLATENCY_BT) || (output_tokens_available != 0)) {
        printf("FAIL. INCORRECT TOKENS ON BOOT. produced tokens available %d, input slots available %d\n", output_tokens_available, input_token_capacity);
        exit(1);
    }

    printf("On init, %d token slots available on input.\n", input_token_capacity);
    uint32_t token_bytes_produced = 0;
    token_bytes_produced = push(
            dma_addr,
            pcis_write_bufs[1],
            BUFWIDTH*input_token_capacity);
    if (token_bytes_produced != input_token_capacity*BUFWIDTH) {
        printf("ERR MISMATCH!\n");
        exit(1);
    }*/
    // idrk if we need to worry aobut this quite yet
    return;
}

//#define TOKENVERIFY

void simplenic_t::tick() {
    struct timespec tstart, tend;
    printf("im ticking\n");
    printf("BUFWIDTH: %d\n", BUFWIDTH);
    printf("BUFBYTES: %d\n", BUFBYTES);
    //#define DEBUG_NIC_PRINT
    while (true) { // break when we don't have 5k tokens
        uint32_t tokens_this_round = 0;
        printf("current round: %d\n", currentround);
        uint32_t output_tokens_available = read(mmio_addrs->number_of_tokens);
        //uint32_t input_token_capacity = SIMLATENCY_BT - read(mmio_addrs->incoming_count);

        // we will read/write the min of tokens available / token input capacity
        tokens_this_round = output_tokens_available;

        // read into read_buffer
        printf("tokens this round: %d\n", tokens_this_round);
        uint32_t token_bytes_obtained_from_fpga = 0;
        token_bytes_obtained_from_fpga = pull(
                dma_addr,
                pcis_read_bufs[currentround],
                BUFWIDTH * tokens_this_round);
        printf("token bytes from fpga: %d\n",token_bytes_obtained_from_fpga);
        pcis_read_bufs[currentround][BUFBYTES] = 1;
        for(int i = 0; i < BUFBYTES; i ++) {
            printf("printing out read buffer ");
            printf("%d\n", pcis_read_bufs[currentround][i]);

        }  
        
        if (token_bytes_obtained_from_fpga != tokens_this_round * BUFWIDTH) {
            printf("ERR MISMATCH! on reading tokens out. actually read %d bytes, wanted %d bytes.\n", token_bytes_obtained_from_fpga, BUFWIDTH * tokens_this_round);
            printf("errno: %s\n", strerror(errno));
            exit(1);
        }

        /*if (!loopback) {
            volatile uint8_t * polladdr = (uint8_t*)(pcis_write_bufs[currentround] + BUFBYTES);
            while (*polladdr == 0) { ; }
        }

        uint32_t token_bytes_sent_to_fpga = 0;
        token_bytes_sent_to_fpga = push(
                dma_addr,
                pcis_write_bufs[currentround],
                BUFWIDTH * tokens_this_round);
        pcis_write_bufs[currentround][BUFBYTES] = 0;
        if (token_bytes_sent_to_fpga != tokens_this_round * BUFWIDTH) {
            printf("ERR MISMATCH! on writing tokens in. actually wrote in %d bytes, wanted %d bytes.\n", token_bytes_sent_to_fpga, BUFWIDTH * tokens_this_round);
            printf("errno: %s\n", strerror(errno));
            exit(1);
        }*/

        currentround = (currentround + 1) % 2;
    }
}

#endif // #ifdef SIMPLENICBRIDGEMODULE_struct_guard

