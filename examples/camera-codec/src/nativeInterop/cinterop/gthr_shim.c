/*
 * libde265-kmp's mingwX64 static library was built with a GCC whose C++
 * runtime expects the win32-threads gthread mutex shims. The MinGW runtime
 * that Kotlin/Native ships does not include those four symbols, so the example
 * provides them on top of the Windows critical section they wrap.
 */
#include <windows.h>

typedef CRITICAL_SECTION gthr_mutex_t;

void __gthr_win32_mutex_init_function(void *mutex) {
    InitializeCriticalSection((gthr_mutex_t *) mutex);
}

int __gthr_win32_mutex_lock(void *mutex) {
    EnterCriticalSection((gthr_mutex_t *) mutex);
    return 0;
}

int __gthr_win32_mutex_unlock(void *mutex) {
    LeaveCriticalSection((gthr_mutex_t *) mutex);
    return 0;
}

int __gthr_win32_mutex_destroy(void *mutex) {
    DeleteCriticalSection((gthr_mutex_t *) mutex);
    return 0;
}
