import { Body, Controller, Post, Req, UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { ConfigService } from '@nestjs/config';
import { IsEmail, IsNumber, IsString, Min, Max, MaxLength } from 'class-validator';
import { PaymentsService } from './payments.service';
class DepositDto { @IsNumber() @Min(100) @Max(5000000) amount: number; @IsEmail() email: string; }
class VirtualAccountDto extends DepositDto { @IsString() @MaxLength(40) firstName: string; @IsString() @MaxLength(40) lastName: string; }
@Controller('payments')
export class PaymentsController {
  constructor(private payments: PaymentsService, private jwt: JwtService, private config: ConfigService) {}
  private async userId(req: any) {
    const h = req.headers.authorization || '';
    if (!h.startsWith('Bearer ')) throw new UnauthorizedException();
    try { return (await this.jwt.verifyAsync(h.slice(7), { secret: this.config.getOrThrow('JWT_ACCESS_SECRET') })).sub; } catch { throw new UnauthorizedException(); }
  }
  @Post('deposits/checkout') async deposit(@Req() req: any, @Body() dto: DepositDto) { return this.payments.initializeDeposit(await this.userId(req), dto.amount, dto.email); }
  @Post('virtual-accounts/dynamic') async virtualAccount(@Req() req: any, @Body() dto: VirtualAccountDto) { return this.payments.createDynamicVirtualAccount(await this.userId(req), dto.amount, dto.email, dto.firstName, dto.lastName); }
  @Post('transfers') transfer() { return this.payments.createTransfer(); }
}
