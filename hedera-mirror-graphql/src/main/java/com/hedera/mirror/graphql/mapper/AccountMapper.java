package com.hedera.mirror.graphql.mapper;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2023 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.hedera.mirror.common.domain.entity.Entity;
import com.hedera.mirror.graphql.viewmodel.Account;

@Mapper(uses = CommonMapper.class)
public interface AccountMapper {
    @Mapping(target = "alias", ignore = true)
    @Mapping(source = "ethereumNonce", target = "nonce")
    Account map(Entity source);
}
