/*
 * Copyright (c) 2017 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.rx.observers.adapter

import io.reactivex.MaybeObserver
import io.reactivex.disposables.Disposable

abstract class MaybeObserverAdapter<T> : MaybeObserver<T> {

  override fun hashCode() = super.hashCode()

  override fun equals(other: Any?) = super.equals(other)

  override fun onSubscribe(d: Disposable) = Unit

  override fun onSuccess(value: T) = Unit

  override fun onError(e: Throwable) = Unit

  override fun onComplete() = Unit
}
