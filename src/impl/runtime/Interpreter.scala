package impl.runtime

import impl.logic.Symbol.{VariableSymbol => VSym}
import impl.logic._
import syntax.Values.RichValue

import scala.util.Try

object Interpreter {

  /**
   * Returns the value of evaluating the given term `t` under the given environment `env`.
   *
   * If the environment is not specified, the empty environment is assumed.
   *
   * Throws an exception if the term is not reducible, i.e. it contains free variables under the environment.
   */
  def evaluate(t: Term, env: Map[VSym, Value] = Map.empty): Value = t match {
    case Term.Unit => Value.Unit
    case Term.Bool(b) => Value.Bool(b)
    case Term.Int(i) => Value.Int(i)
    case Term.Str(s) => Value.Str(s)
    case Term.Set(xs) => Value.Set(xs.map(x => evaluate(x, env)))

    case Term.Var(s) => env.get(s) match {
      case None => throw Error.UnboundVariableError(s)
      case Some(v) => v
    }
    case Term.Abs(s, typ, t1) => Value.Abs(s, typ, t1)
    case Term.App(t1, t2) =>
      val Value.Abs(x, _, t3) = evaluate(t1, env)
      val argVal = evaluate(t2, env)
      evaluate(t3.substitute(x, argVal.toTerm), env) // TODO: Eliminate free occurences in argVal and remove environment.

    case Term.Match(t1, rules) =>
      // TODO: Improve code
      val v1 = evaluate(t1, env)
      val matches: List[(Map[VSym, Value], Term)] = rules.flatMap {
        case (p, t2) => unify(p, v1).map(x => (x, t2))
      }
      matches.headOption match {
        case Some((env1, t2)) => evaluate(t2, env ++ env1)
        case None =>
          throw new RuntimeException(s"No match for value ${v1.fmt}. Rule: ${t}")
      }

    case Term.IfThenElse(t1, t2, t3) =>
      val b = evaluate(t1, env).toBool
      if (b)
        evaluate(t2, env)
      else
        evaluate(t3, env)

    case Term.UnaryOp(op, t1) =>
      val v1 = evaluate(t, env)
      apply(op, v1)

    case Term.BinaryOp(op, t1, t2) =>
      val v1 = evaluate(t1, env)
      val v2 = evaluate(t2, env)
      apply(op, v1, v2)

    case Term.Tagged(s, t1, typ) =>
      val v1 = evaluate(t1, env)
      Value.Tagged(s, v1, typ)

    case Term.Tuple2(t1, t2) =>
      val v1 = evaluate(t1, env)
      val v2 = evaluate(t2, env)
      Value.Tuple2(v1, v2)

    case Term.Tuple3(t1, t2, t3) =>
      val v1 = evaluate(t1, env)
      val v2 = evaluate(t2, env)
      val v3 = evaluate(t3, env)
      Value.Tuple3(v1, v2, v3)

    case Term.Tuple4(t1, t2, t3, t4) =>
      val v1 = evaluate(t1, env)
      val v2 = evaluate(t2, env)
      val v3 = evaluate(t3, env)
      val v4 = evaluate(t4, env)
      Value.Tuple4(v1, v2, v3, v4)

    case Term.Tuple5(t1, t2, t3, t4, t5) =>
      val v1 = evaluate(t1, env)
      val v2 = evaluate(t2, env)
      val v3 = evaluate(t3, env)
      val v4 = evaluate(t4, env)
      val v5 = evaluate(t5, env)
      Value.Tuple5(v1, v2, v3, v4, v5)
  }

  /**
   * Optionally returns an environment mapping every free variable
   * in the pattern `p` with the given value `v`.
   */
  def unify(p: Pattern, v: Value): Option[Map[VSym, Value]] = (p, v) match {
    case (Pattern.Wildcard, _) => Some(Map.empty)
    case (Pattern.Var(x), _) => Some(Map(x -> v))

    case (Pattern.Unit, Value.Unit) => Some(Map.empty)
    case (Pattern.Bool(b1), Value.Bool(b2)) if b1 == b2 => Some(Map.empty)
    case (Pattern.Int(i1), Value.Int(i2)) if i1 == i2 => Some(Map.empty)
    case (Pattern.Str(s1), Value.Str(s2)) if s1 == s2 => Some(Map.empty)

    case (Pattern.Tagged(s1, p1), Value.Tagged(s2, v1, _)) if s1 == s2 => unify(p1, v1)

    case (Pattern.Tuple2(p1, p2), Value.Tuple2(v1, v2)) =>
      for (env1 <- unify(p1, v1);
           env2 <- unify(p2, v2))
      yield env1 ++ env2
    case (Pattern.Tuple3(p1, p2, p3), Value.Tuple3(v1, v2, v3)) =>
      for (env1 <- unify(p1, v1);
           env2 <- unify(p2, v2);
           env3 <- unify(p3, v3))
      yield env1 ++ env2 ++ env3
    case (Pattern.Tuple4(p1, p2, p3, p4), Value.Tuple4(v1, v2, v3, v4)) =>
      for (env1 <- unify(p1, v1);
           env2 <- unify(p2, v2);
           env3 <- unify(p3, v3);
           env4 <- unify(p4, v4))
      yield env1 ++ env2 ++ env3 ++ env4
    case (Pattern.Tuple5(p1, p2, p3, p4, p5), Value.Tuple5(v1, v2, v3, v4, v5)) =>
      for (env1 <- unify(p1, v1);
           env2 <- unify(p2, v2);
           env3 <- unify(p3, v3);
           env4 <- unify(p4, v4);
           env5 <- unify(p5, v5))
      yield env1 ++ env2 ++ env3 ++ env4 ++ env5

    case _ => None
  }

  /**
   * Optionally returns the value of evaluating the given term `t` under the given environment `env`.
   *
   * If the environment is not specified, the empty environment is assumed.
   *
   * Returns `None` iff the term does not reduce to a value under the given environment.
   */
  def evaluateOpt(t: Term, env: Map[VSym, Value] = Map.empty): Option[Value] =
    Try(evaluate(t, env)).toOption

  /**
   * Returns a predicate with ground terms for the given predicate `p` under the environment `env`.
   *
   * If the environment is not specified, the empty environment is assumed.
   */
  def evaluatePredicate(p: Predicate, env: Map[VSym, Value] = Map.empty): Predicate.GroundPredicate =
    Predicate.GroundPredicate(p.name, p.terms map (t => evaluate(t, env)), p.typ)

  /**
   * Optionally returns a predicate with ground terms for the given predicate `p` under the environment `env`.
   *
   * If the environment is not specified, the empty environment is assumed.
   *
   * Returns `None` if the predicate contains non-ground terms after evaluation.
   */
  def evaluatePredicateOpt(p: Predicate, env: Map[VSym, Value] = Map.empty): Option[Predicate.GroundPredicate] = {
    val terms = p.terms.map(t => evaluateOpt(t, env))
    if (terms.exists(_.isEmpty))
      None
    else
      Some(Predicate.GroundPredicate(p.name, terms.map(_.get), p.typ))
  }

  /**
   * Returns the result of applying the unary operator `op` to the given value `v`.
   */
  def apply(op: UnaryOperator, v: Value): Value = op match {
    case UnaryOperator.Not => Value.Bool(!v.toBool)
    case UnaryOperator.UnaryPlus => Value.Int(v.toInt)
    case UnaryOperator.UnaryMinus => Value.Int(-v.toInt)
  }

  /**
   * Returns the result of applying the binary operator `op` to the given values `v1` and `v2`.
   */
  def apply(op: BinaryOperator, v1: Value, v2: Value): Value = op match {
    case BinaryOperator.Plus => Value.Int(v1.toInt + v2.toInt)
    case BinaryOperator.Minus => Value.Int(v1.toInt - v2.toInt)
    case BinaryOperator.Times => Value.Int(v1.toInt * v2.toInt)
    case BinaryOperator.Divide => Value.Int(v1.toInt / v2.toInt)
    case BinaryOperator.Modulo => Value.Int(v1.toInt % v2.toInt)
    case BinaryOperator.Less => Value.Bool(v1.toInt < v2.toInt)
    case BinaryOperator.LessEqual => Value.Bool(v1.toInt <= v2.toInt)
    case BinaryOperator.Greater => Value.Bool(v1.toInt > v2.toInt)
    case BinaryOperator.GreaterEqual => Value.Bool(v1.toInt >= v2.toInt)
    case BinaryOperator.Equal => Value.Bool(v1 == v2)
    case BinaryOperator.NotEqual => Value.Bool(v1 != v2)
    case BinaryOperator.Union => Value.Set(v1.toSet ++ v2.toSet)
    case BinaryOperator.Subset => Value.Bool(v1.toSet subsetOf v2.toSet)
  }
}
