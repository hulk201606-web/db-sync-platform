/** mock 层抛出的业务错误，由 client 转换为 ApiError（带 HTTP 状态与 code） */
export class MockError extends Error {
  readonly code: string;
  readonly status: number;
  readonly data: unknown;

  constructor(code: string, message: string, status = 400, data?: unknown) {
    super(message);
    this.name = 'MockError';
    this.code = code;
    this.status = status;
    this.data = data;
  }
}
