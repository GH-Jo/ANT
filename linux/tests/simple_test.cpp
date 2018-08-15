/* Copyright 2017-2018 All Rights Reserved.
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *  Injung Hwang (sinban04@gmail.com)
 *  
 * [Contact]
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0(the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <API.h>
#include <BtServerAdapter.h>
#include <WfdServerAdapter.h>
#include <EthServerAdapter.h>

#include "csv.h"

#include <thread>

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/time.h>

using namespace cm;

#define DEBUG_SHOW_DATA 0
#define DEBUG_SHOW_TIME 0

#if DEBUG_SHOW_TIME == 1
struct timeval start, end;
#endif

FILE *fp;
std::condition_variable end_lock;

void receiving_thread() {
  void *buf = NULL;
  printf("[INFO] Receiving thread created! tid: %d\n", (unsigned int)syscall(224));

  while (true) {
    int ret = cm::receive(&buf);
#if DEBUG_SHOW_DATA == 1
    printf("Recv %d> %s\n\n", ret, reinterpret_cast<char *>(buf));
#endif
#if DEBUG_SHOW_TIME == 1
    gettimeofday(&end, NULL);
    printf("%ld %ld \n", end.tv_sec - start.tv_sec, end.tv_usec - start.tv_usec);  
#endif

    if(buf) free(buf);
    end_lock.notify_one();
  }
}

static char *rand_string(char *str, size_t size)
{
  const char charset[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  if (size) {
    --size;
    for (size_t n = 0; n < size; n++) {
      int key = rand() % (int) (sizeof charset - 1);
      str[n] = charset[key];
    }
    str[size] = '\0';
  }
  return str;
}

int main(int argc, char** argv) {

  cm::start_sc();
  //EthServerAdapter ethAdapter(2345, "Eth", 2345);
  BtServerAdapter btControl(2345, "BtCt", "150e8400-1234-41d4-a716-446655440000");
  BtServerAdapter btData(3333, "BtDt", "150e8400-1234-41d4-a716-446655440001");

  printf("Step 1. Initializing Network Adapters\n");

  // printf("  a) Control Adapter: TCP over Ethernet\n");
  // cm::register_control_adapter(&ethAdapter);
  printf("  a) Control Adapter: RFCOMM over Bluetooth\n");
  cm::register_control_adapter(&btControl);
  printf("  b) Data Adapter: RFCOMM over Bluetooth\n");
  cm::register_data_adapter(&btData);

  char* temp_buf;

  std::thread(receiving_thread).detach();

#define TEST_DATA_SIZE (5*1024)
  printf("Step 2. Send Test Data (%dB)\n", TEST_DATA_SIZE);
  int i;
  for(i=0; i<1; i++) {
    sleep(2);
    temp_buf = (char*)calloc(TEST_DATA_SIZE, sizeof(char));
    cm::send(temp_buf, TEST_DATA_SIZE);
    sleep(10);
    free(temp_buf);
  }

  printf("Wait for 30 seconds...\n");
  sleep(30);

  printf("Finish Workload\n");

  cm::stop_sc();

  return 0;
}
