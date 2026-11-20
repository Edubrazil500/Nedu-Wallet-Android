import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { ConfigService } from '@nestjs/config';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';

@Module({
  imports: [
    JwtModule.registerAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService) => {
        const secret = config.getOrThrow<string>('JWT_ACCESS_SECRET');
        const expiresIn = Number(config.get<string>('JWT_ACCESS_TTL_SECONDS', '900'));

        if (!Number.isSafeInteger(expiresIn) || expiresIn <= 0) {
          throw new Error('JWT_ACCESS_TTL_SECONDS must be a positive integer.');
        }

        return {
          secret,
          signOptions: { expiresIn },
        };
      },
    }),
  ],
  controllers: [AuthController],
  providers: [AuthService],
  exports: [AuthService],
})
export class AuthModule {}
