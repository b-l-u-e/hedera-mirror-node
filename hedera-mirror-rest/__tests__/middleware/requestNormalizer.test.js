/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
 *
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
 */

import {normalizeRequestQueryParams} from '../../middleware/requestNormalizer.js';
import request from 'supertest';
import {openApiValidator} from '../../middleware/index.js';
import express from 'express';

const app = express();
openApiValidator(app);

describe('request normalizer', () => {
  const testSpecs = [
    {
      description: 'Unknown path with no query parameters',
      input: '/api/v1/unknown',
      expected: '/api/v1/unknown',
    },
    {
      description: 'Unknown path and unknown query parameters are discarded',
      input: '/api/v1/unknown?b_param=3&a_param=5',
      expected: '/api/v1/unknown',
    },
    {
      description: 'Unknown array parameters are discarded',
      input: '/api/v1/unknown?a_param=3&a_param=5',
      expected: '/api/v1/unknown',
    },
    {
      description: 'Unknown path with path parameter',
      input: '/api/v1/unknown/100/transactions?b_param=3&a_param=5',
      expected: '/api/v1/unknown/100/transactions',
    },
    {
      description: 'Unknown path with invalid path parameter',
      input: '//v1/unknown//transactions??=false',
      expected: '//v1/unknown//transactions',
    },
    {
      description: 'Block query parameters are added',
      input: '/api/v1/blocks',
      expected: '/api/v1/blocks?limit=25&order=desc',
    },
    {
      description: 'Known and unknown query parameters',
      input: '/api/v1/blocks?order=asc&unknown=3',
      expected: '/api/v1/blocks?limit=25&order=asc',
    },
    {
      description: 'Case insensitive query parameter is discarded',
      input: '/api/v1/blocks?LimIT=10',
      expected: '/api/v1/blocks?limit=25&order=desc',
    },
    {
      description: 'Non sortable parameters are not sorted',
      input: '/api/v1/blocks?order=asc&block.number=3&block.number=2',
      expected: '/api/v1/blocks?block.number=3&block.number=2&limit=25&order=asc',
    },
    {
      description: 'Account parameters are added',
      input: '/api/v1/accounts',
      expected: '/api/v1/accounts?balance=true&limit=25&order=asc',
    },
    {
      description: 'Eq and ne parameters',
      input: '/api/v1/accounts?account.id=eq:1001&account.id=ne:3',
      expected: '/api/v1/accounts?account.id=eq:1001&account.id=ne:3&balance=true&limit=25&order=asc',
    },
    {
      description: 'Default query parameters are added to existing query parameters and sorted',
      input: '/api/v1/accounts?limit=3&account.id=gt:0.0.20&account.id=lt:0.0.21',
      expected: '/api/v1/accounts?account.id=gt:0.0.20&account.id=lt:0.0.21&balance=true&limit=3&order=asc',
    },
    {
      description: 'Multiple instances of the same parameter are allowed',
      input: '/api/v1/accounts?limit=2&limit=3',
      expected: '/api/v1/accounts?balance=true&limit=2&limit=3&order=asc',
    },
    {
      description: 'Accounts with path parameter has default parameter added',
      input: '/api/v1/accounts/0.0.1001/nfts',
      expected: '/api/v1/accounts/0.0.1001/nfts?limit=25&order=desc',
    },
    {
      description:
        'Accounts with path parameter and query parameters have default parameter added and parameters sorted',
      input: '/api/v1/accounts/0.0.1001/nfts?serialnumber=gte:2&spender.id=gte:2004&token.id=gte:1500&order=asc',
      expected:
        '/api/v1/accounts/0.0.1001/nfts?limit=25&order=asc&serialnumber=gte:2&spender.id=gte:2004&token.id=gte:1500',
    },
    {
      description: 'Contract result log parameters are added and sorted',
      input: '/api/v1/contracts/results/logs?index=lt:1&timestamp=1639010141.000000000&topic0=A',
      expected: '/api/v1/contracts/results/logs?index=lt:1&limit=25&order=desc&timestamp=1639010141.000000000&topic0=A',
    },
    {
      description: 'No parameters are added',
      input: '/api/v1/contracts/62cf9068fed962cf9aaabbb962cf9068fed9dddd',
      expected: '/api/v1/contracts/62cf9068fed962cf9aaabbb962cf9068fed9dddd',
    },
    {
      description:
        'Contracts with path parameter and query parameter have default parameters added and parameters sorted',
      input: '/api/v1/contracts/62cf9068fed962cf9aaabbb962cf9068fed9dddd/results?limit=3',
      expected: '/api/v1/contracts/62cf9068fed962cf9aaabbb962cf9068fed9dddd/results?internal=false&limit=3&order=desc',
    },
    {
      description: 'Accounts nfts with path parameter and query parameters',
      input: '/api/v1/accounts/0.0.1001/nfts?token.id=gte:1500&serialnumber=gte:2&spender.id=gte:2004&order=asc',
      expected:
        '/api/v1/accounts/0.0.1001/nfts?limit=25&order=asc&serialnumber=gte:2&spender.id=gte:2004&token.id=gte:1500',
    },
    {
      description: 'Token nfts with path parameter',
      input: '/api/v1/tokens/1500/nfts/2/transactions',
      expected: '/api/v1/tokens/1500/nfts/2/transactions?limit=25&order=desc',
    },
    {
      description: 'Token nfts with shard realm num path parameter',
      input: '/api/v1/tokens/0.0.1500/nfts/2/transactions',
      expected: '/api/v1/tokens/0.0.1500/nfts/2/transactions?limit=25&order=desc',
    },
    {
      description: 'Token nfts with path parameter and query parameters',
      input: '/api/v1/tokens/1500/nfts/2/transactions?timestamp=gte:1234567890.000000005&order=asc',
      expected: '/api/v1/tokens/1500/nfts/2/transactions?limit=25&order=asc&timestamp=gte:1234567890.000000005',
    },
    {
      description: 'Accounts nfts with shard realm num path parameter and query parameters',
      input: '/api/v1/accounts/0.0.1001/nfts?token.id=gte:1500&serialnumber=gte:2&spender.id=gte:2004',
      expected:
        '/api/v1/accounts/0.0.1001/nfts?limit=25&order=desc&serialnumber=gte:2&spender.id=gte:2004&token.id=gte:1500',
    },
    {
      description: 'Topics messages with path parameter',
      input: '/api/v1/topics/7/messages',
      expected: '/api/v1/topics/7/messages?limit=25&order=asc',
    },
    {
      description: 'Balance is not sorted',
      input: '/api/v1/accounts?balance=true&balance=false',
      expected: '/api/v1/accounts?balance=true&balance=false&limit=25&order=asc',
    },
    {
      description: 'Timestamp values are not sorted',
      input: '/api/v1/transactions?timestamp=1639010141.000000001&timestamp=1639010141.000000000',
      expected:
        '/api/v1/transactions?limit=25&order=desc&timestamp=1639010141.000000001&timestamp=1639010141.000000000',
    },
  ];

  const setupRoute = (spec) => {
    const route = spec.input.split('?')[0];
    app.get(route, (req, res) => {
      const actual = normalizeRequestQueryParams(req.openapi?.openApiRoute, req.path, req.query);
      res.set('actual', actual);
      res.sendStatus(200);
    });
  };

  testSpecs.forEach((spec) => {
    setupRoute(spec);
    test(spec.description, async () => {
      await request(app)
        .get(spec.input)
        .then((res) => {
          expect(res.get('actual')).toEqual(spec.expected);
        });
    });
  });
});
