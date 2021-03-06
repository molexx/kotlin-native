/*
 * Copyright 2010-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "Memory.h"
#include "Types.h"

namespace {

// TODO: an ugly hack with fixed offsets.
constexpr int referredOffset = 0;
constexpr int lockOffset = sizeof(void*);

#if !KONAN_NO_THREADS

inline void lock(int32_t* address) {
    while (__sync_val_compare_and_swap(address, 0, 1) == 1);
}

inline void unlock(int32_t* address) {
    int old = __sync_val_compare_and_swap(address, 1, 0);
    RuntimeAssert(old == 1, "Incorrect lock state");
}

#endif

}  // namespace

extern "C" {

OBJ_GETTER(makeWeakReferenceCounter, void*);
OBJ_GETTER(makeObjCWeakReferenceImpl, void*);

// See Weak.kt for implementation details.
// Retrieve link on the counter object.
OBJ_GETTER(Konan_getWeakReferenceImpl, ObjHeader* referred) {
  MetaObjHeader* meta = referred->meta_object();

#if KONAN_OBJC_INTEROP
  if (IsInstance(referred, theObjCObjectWrapperTypeInfo)) {
    RETURN_RESULT_OF(makeObjCWeakReferenceImpl, meta->associatedObject_);
  }
#endif // KONAN_OBJC_INTEROP

  if (meta->counter_ == nullptr) {
     ObjHolder counterHolder;
     // Cast unneeded, just to emphasize we store an object reference as void*.
     ObjHeader* counter = makeWeakReferenceCounter(reinterpret_cast<void*>(referred), counterHolder.slot());
     UpdateRefIfNull(&meta->counter_, counter);
  }
  RETURN_OBJ(meta->counter_);
}

// Materialize a weak reference to either null or the real reference.
OBJ_GETTER(Konan_WeakReferenceCounter_get, ObjHeader* counter) {
  ObjHeader** referredAddress = reinterpret_cast<ObjHeader**>(reinterpret_cast<char*>(counter + 1) + referredOffset);
#if KONAN_NO_THREADS
  RETURN_OBJ(*referredAddress);
#else
  int32_t* lockAddress = reinterpret_cast<int32_t*>(reinterpret_cast<char*>(counter + 1) + lockOffset);
  // Spinlock.
  lock(lockAddress);
  ObjHolder holder(*referredAddress);
  unlock(lockAddress);
  RETURN_OBJ(holder.obj());
#endif
}

void WeakReferenceCounterClear(ObjHeader* counter) {
  ObjHeader** referredAddress = reinterpret_cast<ObjHeader**>(reinterpret_cast<char*>(counter + 1) + referredOffset);
  // Note, that we don't do UpdateRef here, as reference is weak.
#if KONAN_NO_THREADS
  *referredAddress = nullptr;
#else
  int32_t* lockAddress = reinterpret_cast<int32_t*>(reinterpret_cast<char*>(counter + 1) + lockOffset);
  // Spinlock.
  lock(lockAddress);
  *referredAddress = nullptr;
  unlock(lockAddress);
#endif
}

}  // extern "C"
