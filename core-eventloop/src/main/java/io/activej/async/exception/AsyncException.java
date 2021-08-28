/*
 * Copyright (C) 2020 ActiveJ LLC.
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

package io.activej.async.exception;

import io.activej.common.ApplicationSettings;

public class AsyncException extends Exception {
	public static final boolean WITH_STACK_TRACE = ApplicationSettings.getBoolean(AsyncException.class, "withStackTrace", false);

	public AsyncException() {
		super();
	}

	public AsyncException(String message) {
		super(message);
	}

	public AsyncException(String message, Exception cause) {
		super(message, cause);
	}

	@Override
	public Throwable fillInStackTrace() {
		return WITH_STACK_TRACE ? super.fillInStackTrace() : this;
	}
}
