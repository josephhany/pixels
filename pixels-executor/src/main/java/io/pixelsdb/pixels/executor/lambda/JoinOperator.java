/*
 * Copyright 2022 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.executor.lambda;

import io.pixelsdb.pixels.executor.join.JoinAlgorithm;
import io.pixelsdb.pixels.executor.lambda.input.JoinInput;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author hank
 * @date 05/06/2022
 */
public interface JoinOperator
{
    List<JoinInput> getJoinInputs();

    JoinAlgorithm getJoinAlgo();

    void setChild(JoinOperator child, boolean smallChild);

    /**
     * Execute this join operator recursively.
     *
     * @return the join outputs.
     */
    CompletableFuture<?>[] execute();

    /**
     * Only execute the previous stages (if any) before the last stage recursively.
     * @return empty array if the previous stages do not exist
     */
    CompletableFuture<?>[] executePrev();
}
